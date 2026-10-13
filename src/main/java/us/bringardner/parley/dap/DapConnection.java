/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 */
package us.bringardner.parley.dap;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import us.bringardner.parley.dap.protocol.AdapterDispatcher;
import us.bringardner.parley.dap.protocol.ClientDispatcher;
import us.bringardner.parley.dap.protocol.DebugAdapterProxy;
import us.bringardner.parley.dap.protocol.DebugClientProxy;
import us.bringardner.parley.dap.protocol.IDebugAdapter;
import us.bringardner.parley.dap.protocol.IDebugClient;

/**
 * A DAP conversation over a pair of streams: a socket's, or a process's stdin and stdout
 * (how IDEs usually start a debug adapter).
 * <p>
 * An adapter:
 * <pre>
 * DapConnection c = DapConnection.adapter(client -&gt; new MyAdapter(client), in, out);
 * c.closed().join();
 * </pre>
 * A client:
 * <pre>
 * DapConnection c = DapConnection.client(myEventHandler, in, out);
 * IDebugAdapter adapter = c.getAdapter();
 * Capabilities caps = adapter.initialize(new InitializeRequestArguments().setAdapterID("x")).get();
 * </pre>
 * Messages are read on a thread of the connection's own; see DapEndpoint for how they're
 * handled.
 */
public final class DapConnection implements Closeable {

	private static final AtomicInteger count = new AtomicInteger();

	private final InputStream in;
	private final OutputStream out;
	private final DapEndpoint endpoint;
	private final CompletableFuture<Void> closed = new CompletableFuture<>();
	private volatile IDebugClient client;
	private volatile IDebugAdapter adapter;
	private volatile boolean adapterSide;
	private volatile Consumer<Throwable> errorListener = e -> {};
	private Thread reader;
	private final int maxMessage;

	private DapConnection(InputStream in, OutputStream out, int maxMessage) {
		this.in = in;
		this.out = out;
		this.maxMessage = maxMessage;
		this.endpoint = new DapEndpoint(json -> {
			synchronized (out) {
				DapFraming.write(out, json);
			}
		}, null);
		endpoint.setErrorListener(e -> errorListener.accept(e));
	}

	/**
	 * Serves an adapter: requests that arrive go to the adapter the factory makes; the factory
	 * gets the client (to send events and reverse requests to). Reading starts at once.
	 */
	public static DapConnection adapter(Function<IDebugClient,IDebugAdapter> factory, InputStream in, OutputStream out) {
		DapConnection c = new DapConnection(in, out, DapFraming.DEFAULT_MAX_MESSAGE);
		c.adapterSide = true;
		c.client = new DebugClientProxy(c.endpoint, e -> c.errorListener.accept(e));
		c.adapter = factory.apply(c.client);
		c.endpoint.setHandler(new AdapterDispatcher(c.adapter));
		c.start();
		return c;
	}

	/**
	 * A client: events and reverse requests that arrive go to client; getAdapter() sends
	 * requests. Reading starts at once.
	 */
	public static DapConnection client(IDebugClient client, InputStream in, OutputStream out) {
		DapConnection c = new DapConnection(in, out, DapFraming.DEFAULT_MAX_MESSAGE);
		c.client = client;
		c.adapter = new DebugAdapterProxy(c.endpoint);
		c.endpoint.setHandler(new ClientDispatcher(client, e -> c.errorListener.accept(e)));
		c.start();
		return c;
	}

	private void start() {
		reader = new Thread(this::read, "DAP reader "+count.incrementAndGet());
		reader.setDaemon(true);
		reader.start();
	}

	private void read() {
		Throwable why = null;
		try {
			while( true ) {
				String json = DapFraming.read(in, maxMessage);
				if( json == null ) {
					break;
				}
				endpoint.receive(json);
			}
		} catch (IOException e) {
			why = e;
		} catch (RuntimeException e) {
			why = e;
			errorListener.accept(e);
		}
		endpoint.closed(why);
		closeStreams();
		closed.complete(null);
	}

	/** Told about messages that couldn't be read or handled, and events that couldn't be sent. */
	public void setErrorListener(Consumer<Throwable> listener) {
		this.errorListener = listener == null ? e -> {} : listener;
	}

	/** The other end, for an adapter: send it events and reverse requests. Null for a client. */
	public IDebugClient getClient() {
		return adapterSide ? client : null;
	}

	/** The other end, for a client: send it requests. Null for an adapter. */
	public IDebugAdapter getAdapter() {
		return adapterSide ? null : adapter;
	}

	public DapEndpoint getEndpoint() {
		return endpoint;
	}

	/** Completes when the connection has closed (the other end went, or close()). */
	public CompletableFuture<Void> closed() {
		return closed;
	}

	public boolean isClosed() {
		return closed.isDone();
	}

	/** Closes the streams; the reader ends and requests still waiting fail. */
	@Override
	public void close() {
		closeStreams();
		Thread r = reader;
		if( r != null && r != Thread.currentThread()) {
			try {
				r.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void closeStreams() {
		try {
			in.close();
		} catch (IOException e) {
			// closed
		}
		try {
			out.close();
		} catch (IOException e) {
			// closed
		}
	}
}
