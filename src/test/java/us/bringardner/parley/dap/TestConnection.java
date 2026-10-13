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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.dap.protocol.Breakpoint;
import us.bringardner.parley.dap.protocol.Capabilities;
import us.bringardner.parley.dap.protocol.ContinueArguments;
import us.bringardner.parley.dap.protocol.ContinueResponseBody;
import us.bringardner.parley.dap.protocol.IDebugAdapter;
import us.bringardner.parley.dap.protocol.IDebugClient;
import us.bringardner.parley.dap.protocol.InitializeRequestArguments;
import us.bringardner.parley.dap.protocol.NextArguments;
import us.bringardner.parley.dap.protocol.RunInTerminalRequestArguments;
import us.bringardner.parley.dap.protocol.RunInTerminalResponseBody;
import us.bringardner.parley.dap.protocol.SetBreakpointsArguments;
import us.bringardner.parley.dap.protocol.SetBreakpointsResponseBody;
import us.bringardner.parley.dap.protocol.Source;
import us.bringardner.parley.dap.protocol.SourceBreakpoint;
import us.bringardner.parley.dap.protocol.StoppedEventBody;
import us.bringardner.parley.dap.protocol.ThreadsResponseBody;

/** An adapter and a client talking over a pair of pipes. */
public class TestConnection {

	/** A small adapter: breakpoints, continue (which stops again), an error, a custom request. */
	static class Adapter implements IDebugAdapter {
		final IDebugClient client;
		volatile String initializedOrder = "";

		Adapter(IDebugClient client) {
			this.client = client;
		}

		@Override
		public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
			DapEndpoint.afterResponse(()->{
				initializedOrder += "event";
				client.initialized();
			});
			initializedOrder += "response,";
			return CompletableFuture.completedFuture(new Capabilities().setSupportsConfigurationDoneRequest(true));
		}

		@Override
		public CompletableFuture<SetBreakpointsResponseBody> setBreakpoints(SetBreakpointsArguments args) {
			List<Breakpoint> ret = new ArrayList<>();
			int id = 1;
			for(SourceBreakpoint sb : args.getBreakpoints()) {
				ret.add(new Breakpoint().setId(id++).setVerified(true).setLine(sb.getLine()));
			}
			return CompletableFuture.completedFuture(new SetBreakpointsResponseBody().setBreakpoints(ret));
		}

		@Override
		public CompletableFuture<ContinueResponseBody> continue_(ContinueArguments args) {
			// answer, then report the next stop
			DapEndpoint.afterResponse(()->client.stopped(new StoppedEventBody().setReason("breakpoint").setThreadId(1)
					.setHitBreakpointIds(List.of(1))));
			return CompletableFuture.completedFuture(new ContinueResponseBody().setAllThreadsContinued(true));
		}

		@Override
		public CompletableFuture<Void> next(NextArguments args) {
			throw new DapException("notStopped", new us.bringardner.parley.dap.protocol.Message()
					.setId(7).setFormat("Thread {t} isn't stopped").setVariables(Map.of("t", "1")).setShowUser(true));
		}

		@Override
		public CompletableFuture<ThreadsResponseBody> threads() {
			// a reverse request, answered by the client
			return client.runInTerminal(new RunInTerminalRequestArguments().setArgs(List.of("ls")).setCwd("/"))
					.thenApply(r -> new ThreadsResponseBody().setThreads(Collections.singletonList(
							new us.bringardner.parley.dap.protocol.Thread().setId(r.getProcessId()).setName("main"))));
		}

		@Override
		public CompletableFuture<Object> otherRequest(String command, Object arguments) {
			if( command.equals("echo")) {
				return CompletableFuture.completedFuture(arguments);
			}
			return IDebugAdapter.super.otherRequest(command, arguments);
		}
	}

	static class Client implements IDebugClient {
		final CompletableFuture<Void> initialized = new CompletableFuture<>();
		final BlockingQueue<StoppedEventBody> stops = new LinkedBlockingQueue<>();
		final BlockingQueue<String> others = new LinkedBlockingQueue<>();

		@Override
		public void initialized() {
			initialized.complete(null);
		}

		@Override
		public void stopped(StoppedEventBody body) {
			stops.add(body);
		}

		@Override
		public void otherEvent(String event, Object body) {
			others.add(event);
		}

		@Override
		public CompletableFuture<RunInTerminalResponseBody> runInTerminal(RunInTerminalRequestArguments args) {
			return CompletableFuture.completedFuture(new RunInTerminalResponseBody().setProcessId(4242));
		}
	}

	private final List<DapConnection> connections = new ArrayList<>();
	private Adapter adapter;

	/** A client connected to an Adapter through pipes. */
	private DapConnection connect(Client client) throws IOException {
		PipedOutputStream toAdapter = new PipedOutputStream();
		PipedInputStream adapterIn = new PipedInputStream(toAdapter, 1 << 16);
		PipedOutputStream toClient = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(toClient, 1 << 16);
		DapConnection a = DapConnection.adapter(c -> adapter = new Adapter(c), adapterIn, toClient);
		DapConnection c = DapConnection.client(client, clientIn, toAdapter);
		connections.add(a);
		connections.add(c);
		return c;
	}

	@AfterEach
	public void close() {
		for(DapConnection c : connections) {
			c.close();
		}
	}

	@Test
	public void requestsResponsesAndEvents() throws Exception {
		Client client = new Client();
		DapConnection c = connect(client);
		IDebugAdapter remote = c.getAdapter();
		Capabilities caps = remote.initialize(new InitializeRequestArguments().setAdapterID("test")).get(10, TimeUnit.SECONDS);
		assertTrue(caps.getSupportsConfigurationDoneRequest());
		client.initialized.get(10, TimeUnit.SECONDS);
		assertEquals("response,event", adapter.initializedOrder);

		SetBreakpointsResponseBody bps = remote.setBreakpoints(new SetBreakpointsArguments()
				.setSource(new Source().setPath("/a.sh"))
				.setBreakpoints(List.of(new SourceBreakpoint().setLine(3), new SourceBreakpoint().setLine(9)))).get(10, TimeUnit.SECONDS);
		assertEquals(2, bps.getBreakpoints().size());
		assertEquals(9, bps.getBreakpoints().get(1).getLine());

		assertTrue(remote.continue_(new ContinueArguments().setThreadId(1)).get(10, TimeUnit.SECONDS).getAllThreadsContinued());
		StoppedEventBody stop = client.stops.poll(10, TimeUnit.SECONDS);
		assertNotNull(stop);
		assertEquals(List.of(1), stop.getHitBreakpointIds());
	}

	@Test
	public void errorsComeBackAsDapExceptions() throws Exception {
		DapConnection c = connect(new Client());
		IDebugAdapter remote = c.getAdapter();
		ExecutionException e = assertThrows(ExecutionException.class, ()->remote.next(new NextArguments().setThreadId(1)).get(10, TimeUnit.SECONDS));
		DapException d = (DapException) e.getCause();
		assertEquals("notStopped", d.getMessage());
		assertEquals(7, d.getError().getId());
		assertEquals("Thread 1 isn't stopped", d.getText());

		// not implemented by the adapter
		e = assertThrows(ExecutionException.class, ()->remote.stepBack(null).get(10, TimeUnit.SECONDS));
		assertEquals("notSupported", e.getCause().getMessage());
		// not in the protocol at all
		e = assertThrows(ExecutionException.class, ()->remote.otherRequest("frobnicate", null).get(10, TimeUnit.SECONDS));
		assertEquals("notSupported", e.getCause().getMessage());
	}

	@Test
	public void reverseAndCustomRequests() throws Exception {
		DapConnection c = connect(new Client());
		IDebugAdapter remote = c.getAdapter();
		// the adapter asks the client to run something (runInTerminal) while answering threads
		ThreadsResponseBody t = remote.threads().get(10, TimeUnit.SECONDS);
		assertEquals(4242, t.getThreads().get(0).getId());
		assertEquals(Map.of("x", 1L), remote.otherRequest("echo", Map.of("x", 1)).get(10, TimeUnit.SECONDS));
	}

	@Test
	public void customEventsReachOtherEvent() throws Exception {
		Client client = new Client();
		connect(client);
		adapter.client.otherEvent("fshdbNote", Map.of("text", "hi"));
		assertEquals("fshdbNote", client.others.poll(10, TimeUnit.SECONDS));
	}

	@Test
	public void closingFailsWaitingRequests() throws Exception {
		Client client = new Client();
		DapConnection c = connect(client);
		CompletableFuture<Void> closed = c.closed();
		// the adapter side goes away
		connections.get(0).close();
		closed.get(10, TimeUnit.SECONDS);
		ExecutionException e = assertThrows(ExecutionException.class, ()->c.getAdapter().threads().get(10, TimeUnit.SECONDS));
		assertTrue(e.getCause() instanceof IOException, String.valueOf(e.getCause()));
		assertFalse(c.getEndpoint().getPendingCount() > 0);
	}
}
