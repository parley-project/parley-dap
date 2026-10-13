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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import us.bringardner.parley.dap.json.Json;
import us.bringardner.parley.dap.json.JsonException;
import us.bringardner.parley.dap.json.JsonValues;
import us.bringardner.parley.dap.protocol.Message;

/**
 * One end of a DAP conversation, whatever carries it (streams, an NIO connection ...): it
 * numbers outgoing messages, matches responses to the requests waiting for them, and hands
 * incoming requests and events to a {@link Handler}.
 * <p>
 * The transport calls {@link #receive(String)} with each message's JSON and gives the endpoint
 * a {@link Sender} that writes one. Requests and events may be sent on any thread.
 * <p>
 * Incoming messages are handled one at a time, in order, on the thread that calls receive
 * (or on the executor, if one is set). A handler returns a future for its response, so it
 * needn't block; the response is sent when the future completes, on whatever thread
 * completes it. A handler must not wait for a response to a request of its own on the
 * receiving thread: that response can only arrive through the same thread.
 */
public class DapEndpoint {

	/** Writes one message (the JSON text of a request, response or event). */
	public interface Sender {
		void send(String json) throws IOException;
	}

	/** What the other end asks and tells: the generated dispatchers implement this. */
	public interface Handler {
		/**
		 * A request.
		 * @param arguments its arguments (parsed JSON, or null)
		 * @return the response body (a JSON value or JsonValue, null for none), or a future
		 *         failed with a DapException for an error response
		 */
		CompletableFuture<Object> request(String command, Object arguments);

		/** An event. */
		void event(String event, Object body);
	}

	private final Sender sender;
	private volatile Handler handler;
	private final AtomicInteger seq = new AtomicInteger();
	private final Map<Integer,Pending> pending = new ConcurrentHashMap<>();
	private volatile Executor executor;
	private volatile Consumer<Throwable> errorListener = e -> {};
	private volatile boolean closed;
	private final Object sendLock = new Object();

	// the request being handled on this thread, for afterResponse
	private static final ThreadLocal<Incoming> current = new ThreadLocal<>();

	private static final class Pending {
		final String command;
		final CompletableFuture<Object> future = new CompletableFuture<>();

		Pending(String command) {
			this.command = command;
		}
	}

	private static final class Incoming {
		Runnable after;
	}

	public DapEndpoint(Sender sender, Handler handler) {
		this.sender = sender;
		this.handler = handler;
	}

	public void setHandler(Handler handler) {
		this.handler = handler;
	}

	/** Handle incoming messages on this executor instead of the receiving thread (one at a time is up to it). */
	public void setExecutor(Executor executor) {
		this.executor = executor;
	}

	/** Told about messages that can't be handled (bad JSON, a response nobody waits for ...). */
	public void setErrorListener(Consumer<Throwable> listener) {
		this.errorListener = listener == null ? e -> {} : listener;
	}

	public boolean isClosed() {
		return closed;
	}

	/**
	 * Called from a request handler (on the thread that called it): run r right after the
	 * response to that request has been sent. DAP needs this for the initialized event, which
	 * must follow the initialize response.
	 * @throws IllegalStateException if this thread isn't handling a request
	 */
	public static void afterResponse(Runnable r) {
		Incoming in = current.get();
		if( in == null ) {
			throw new IllegalStateException("afterResponse is only for request handlers");
		}
		Runnable before = in.after;
		in.after = before == null ? r : ()->{
			before.run();
			r.run();
		};
	}

	// ---- sending

	private void send(Map<String,Object> message) throws IOException {
		if( closed ) {
			throw new IOException("The DAP connection is closed");
		}
		synchronized (sendLock) {
			// seq numbers go out in order
			message.put("seq", seq.incrementAndGet());
			sender.send(Json.write(message));
		}
	}

	private static Map<String,Object> message(String type) {
		Map<String,Object> m = new LinkedHashMap<>();
		m.put("seq", 0);
		m.put("type", type);
		return m;
	}

	/**
	 * Sends a request.
	 * @return its response's body (parsed JSON, null if none); failed with DapException for an
	 *         error response, or IOException if it couldn't be sent or the connection closed
	 */
	public CompletableFuture<Object> request(String command, Object arguments) {
		Map<String,Object> m = message("request");
		m.put("command", command);
		if( arguments != null ) {
			m.put("arguments", arguments);
		}
		Pending p = new Pending(command);
		synchronized (sendLock) {
			try {
				if( closed ) {
					throw new IOException("The DAP connection is closed");
				}
				int n = seq.incrementAndGet();
				m.put("seq", n);
				pending.put(n, p);
				sender.send(Json.write(m));
			} catch (IOException | RuntimeException e) {
				pending.values().remove(p);
				p.future.completeExceptionally(e);
			}
		}
		return p.future;
	}

	/** Sends an event. @throws IOException if it couldn't be sent */
	public void event(String event, Object body) throws IOException {
		Map<String,Object> m = message("event");
		m.put("event", event);
		if( body != null ) {
			m.put("body", body);
		}
		send(m);
	}

	private void respond(int requestSeq, String command, Object body, Throwable error) {
		Map<String,Object> m = message("response");
		m.put("request_seq", requestSeq);
		if( error == null ) {
			m.put("success", true);
			m.put("command", command);
			if( body != null ) {
				m.put("body", body);
			}
		} else {
			Throwable e = unwrap(error);
			m.put("success", false);
			m.put("command", command);
			String msg = e.getMessage();
			if( msg == null || msg.isBlank()) {
				msg = e instanceof UnsupportedOperationException ? "notSupported" : e.getClass().getSimpleName();
			}
			m.put("message", msg);
			Message structured = e instanceof DapException ? ((DapException) e).getError() : null;
			if( structured != null ) {
				Map<String,Object> b = new LinkedHashMap<>();
				b.put("error", structured.toJson());
				m.put("body", b);
			}
		}
		try {
			send(m);
		} catch (IOException | RuntimeException e) {
			errorListener.accept(e);
		}
	}

	static Throwable unwrap(Throwable e) {
		while( (e instanceof CompletionException || e instanceof ExecutionException) && e.getCause() != null ) {
			e = e.getCause();
		}
		return e;
	}

	// ---- receiving

	/** A message arrived (its JSON text). */
	public void receive(String json) {
		Executor ex = executor;
		if( ex != null ) {
			ex.execute(()->handle(json));
		} else {
			handle(json);
		}
	}

	private void handle(String json) {
		Map<String,Object> m;
		try {
			m = Json.parseObject(json);
		} catch (JsonException e) {
			errorListener.accept(e);
			return;
		}
		Object type = m.get("type");
		try {
			if( "request".equals(type)) {
				handleRequest(m);
			} else if( "response".equals(type)) {
				handleResponse(m);
			} else if( "event".equals(type)) {
				Handler h = handler;
				if( h != null ) {
					h.event(JsonValues.string(m.get("event"), "event"), m.get("body"));
				}
			} else {
				errorListener.accept(new JsonException("Unknown DAP message type "+type));
			}
		} catch (JsonException | RuntimeException e) {
			errorListener.accept(e);
		}
	}

	private void handleRequest(Map<String,Object> m) throws JsonException {
		Integer reqSeq = JsonValues.integer(m.get("seq"), "seq");
		String command = JsonValues.string(m.get("command"), "command");
		if( reqSeq == null || command == null ) {
			throw new JsonException("A request needs seq and command");
		}
		Handler h = handler;
		Incoming in = new Incoming();
		CompletableFuture<Object> f;
		current.set(in);
		try {
			f = h == null ? failed(new DapException("notSupported")) : h.request(command, m.get("arguments"));
			if( f == null ) {
				f = CompletableFuture.completedFuture(null);
			}
		} catch (Throwable e) {
			f = failed(e);
		} finally {
			current.remove();
		}
		f.whenComplete((body, error) -> {
			respond(reqSeq, command, body, error);
			if( in.after != null ) {
				try {
					in.after.run();
				} catch (RuntimeException e) {
					errorListener.accept(e);
				}
			}
		});
	}

	private void handleResponse(Map<String,Object> m) throws JsonException {
		Integer reqSeq = JsonValues.integer(m.get("request_seq"), "request_seq");
		Pending p = reqSeq == null ? null : pending.remove(reqSeq);
		if( p == null ) {
			throw new JsonException("A response to no request: "+reqSeq);
		}
		Boolean success = JsonValues.bool(m.get("success"), "success");
		if( Boolean.TRUE.equals(success)) {
			p.future.complete(m.get("body"));
		} else {
			String msg = JsonValues.string(m.get("message"), "message");
			Message error = null;
			Object body = m.get("body");
			if( body instanceof Map && ((Map<?,?>) body).get("error") != null ) {
				try {
					error = Message.fromJson(((Map<?,?>) body).get("error"));
				} catch (JsonException e) {
					// keep the message
				}
			}
			p.future.completeExceptionally(new DapException(msg == null ? p.command+" failed" : msg, error));
		}
	}

	private static CompletableFuture<Object> failed(Throwable e) {
		CompletableFuture<Object> f = new CompletableFuture<>();
		f.completeExceptionally(e);
		return f;
	}

	/** The connection closed: requests still waiting fail. */
	public void closed(Throwable why) {
		closed = true;
		IOException e = new IOException("The DAP connection closed", why);
		for(Pending p : pending.values()) {
			p.future.completeExceptionally(e);
		}
		pending.clear();
	}

	/** Requests sent that have no response yet. */
	public int getPendingCount() {
		return pending.size();
	}
}
