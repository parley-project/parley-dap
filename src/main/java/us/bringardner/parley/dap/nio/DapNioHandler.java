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
package us.bringardner.parley.dap.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import us.bringardner.parley.dap.DapEndpoint;
import us.bringardner.parley.dap.DapFraming;
import us.bringardner.parley.dap.protocol.AdapterDispatcher;
import us.bringardner.parley.dap.protocol.ClientDispatcher;
import us.bringardner.parley.dap.protocol.DebugAdapterProxy;
import us.bringardner.parley.dap.protocol.DebugClientProxy;
import us.bringardner.parley.dap.protocol.IDebugAdapter;
import us.bringardner.parley.dap.protocol.IDebugClient;
import us.bringardner.parley.net.nio.INioConnection;
import us.bringardner.parley.net.nio.INioHandler;
import us.bringardner.parley.net.nio.INioHandlerFactory;

/**
 * Carries DAP on a parley-net non-blocking connection (use ContentLengthFrameDecoder as its
 * decoder).
 * <p>
 * An adapter server:
 * <pre>
 * NioServer server = new NioServer(4711, "dap");
 * server.setDecoderFactory(ContentLengthFrameDecoder::new);
 * server.setHandlerFactory(DapNioHandler.adapters(client -&gt; new MyAdapter(client)));
 * server.setHandlerExecutor(Executors.newCachedThreadPool()); // if the adapter blocks
 * server.startAndWait(5000);
 * </pre>
 * A client:
 * <pre>
 * DapNioHandler h = DapNioHandler.client(myEventHandler);
 * new NioClient().connectAndWait(new InetSocketAddress(host, 4711), h, new ContentLengthFrameDecoder());
 * IDebugAdapter adapter = h.getAdapter();
 * </pre>
 * Handlers run on the reactor's threads (or the handler executor), so an adapter mustn't
 * block in them, nor wait there for a response to a request of its own.
 */
public class DapNioHandler implements INioHandler {

	private final Function<IDebugClient,IDebugAdapter> adapterFactory;
	private volatile INioConnection connection;
	private volatile DapEndpoint endpoint;
	private volatile IDebugAdapter adapter;
	private volatile IDebugClient client;
	private volatile Consumer<Throwable> errorListener = e -> {};
	private final CompletableFuture<Void> closed = new CompletableFuture<>();

	private DapNioHandler(Function<IDebugClient,IDebugAdapter> adapterFactory, IDebugClient clientHandler) {
		this.adapterFactory = adapterFactory;
		DapEndpoint ep = new DapEndpoint(this::send, null);
		ep.setErrorListener(e -> errorListener.accept(e));
		endpoint = ep;
		if( adapterFactory != null ) {
			client = new DebugClientProxy(ep, e -> errorListener.accept(e));
		} else {
			client = clientHandler;
			adapter = new DebugAdapterProxy(ep);
			ep.setHandler(new ClientDispatcher(clientHandler, e -> errorListener.accept(e)));
		}
	}

	/** A handler for each connection an NioServer accepts; the factory makes its adapter. */
	public static INioHandlerFactory adapters(Function<IDebugClient,IDebugAdapter> factory) {
		return ()->new DapNioHandler(factory, null);
	}

	/** One adapter connection (for a server that makes handlers itself). */
	public static DapNioHandler adapter(Function<IDebugClient,IDebugAdapter> factory) {
		return new DapNioHandler(factory, null);
	}

	/** The client end of a connection: events and reverse requests go to client. */
	public static DapNioHandler client(IDebugClient client) {
		return new DapNioHandler(null, client);
	}

	private void send(String json) throws IOException {
		INioConnection c = connection;
		if( c == null || !c.isOpen()) {
			throw new IOException("The DAP connection is closed");
		}
		c.write(ByteBuffer.wrap(DapFraming.frame(json)));
	}

	public void setErrorListener(Consumer<Throwable> listener) {
		this.errorListener = listener == null ? e -> {} : listener;
	}

	@Override
	public void onConnect(INioConnection connection) throws Exception {
		this.connection = connection;
		if( adapterFactory != null ) {
			adapter = adapterFactory.apply(client);
			endpoint.setHandler(new AdapterDispatcher(adapter));
		}
	}

	@Override
	public void onMessage(INioConnection connection, ByteBuffer frame) throws Exception {
		byte[] bytes = new byte[frame.remaining()];
		frame.get(bytes);
		endpoint.receive(new String(bytes, StandardCharsets.UTF_8));
	}

	/** An idle DAP connection stays open (a debugger may be idle for a long time). */
	@Override
	public void onIdle(INioConnection connection) throws Exception {
	}

	@Override
	public void onError(INioConnection connection, Throwable error) {
		errorListener.accept(error);
	}

	@Override
	public void onClose(INioConnection connection) {
		endpoint.closed(null);
		closed.complete(null);
	}

	/** For a client: the adapter at the other end. For an adapter: the one this connection made. */
	public IDebugAdapter getAdapter() {
		return adapter;
	}

	/** For an adapter: the client at the other end. For a client: its own handler. */
	public IDebugClient getClient() {
		return client;
	}

	public DapEndpoint getEndpoint() {
		return endpoint;
	}

	public INioConnection getConnection() {
		return connection;
	}

	public CompletableFuture<Void> closed() {
		return closed;
	}
}
