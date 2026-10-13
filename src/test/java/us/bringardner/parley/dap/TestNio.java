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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.dap.nio.ContentLengthFrameDecoder;
import us.bringardner.parley.dap.nio.DapNioHandler;
import us.bringardner.parley.dap.protocol.ContinueArguments;
import us.bringardner.parley.dap.protocol.IDebugAdapter;
import us.bringardner.parley.dap.protocol.InitializeRequestArguments;
import us.bringardner.parley.dap.protocol.SetBreakpointsArguments;
import us.bringardner.parley.dap.protocol.Source;
import us.bringardner.parley.dap.protocol.SourceBreakpoint;
import us.bringardner.parley.dap.protocol.StoppedEventBody;
import us.bringardner.parley.net.nio.NioClient;
import us.bringardner.parley.net.nio.NioServer;

/** DAP on parley-net's non-blocking server and client, talking to the stream implementation. */
public class TestNio {

	@Test
	public void nioServerWithAStreamClient() throws Exception {
		NioServer server = new NioServer(0, "dap-test");
		server.setBindAddr(InetAddress.getLoopbackAddress());
		server.setDecoderFactory(ContentLengthFrameDecoder::new);
		server.setHandlerFactory(DapNioHandler.adapters(TestConnection.Adapter::new));
		server.setHandlerExecutor(Executors.newCachedThreadPool());
		server.startAndWait(10_000);
		try(Socket s = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
			TestConnection.Client client = new TestConnection.Client();
			DapConnection c = DapConnection.client(client, s.getInputStream(), s.getOutputStream());
			IDebugAdapter adapter = c.getAdapter();
			assertTrue(adapter.initialize(new InitializeRequestArguments().setAdapterID("t")).get(10, TimeUnit.SECONDS)
					.getSupportsConfigurationDoneRequest());
			client.initialized.get(10, TimeUnit.SECONDS);
			assertEquals(4, adapter.setBreakpoints(new SetBreakpointsArguments().setSource(new Source().setPath("/a.sh"))
					.setBreakpoints(List.of(new SourceBreakpoint().setLine(4)))).get(10, TimeUnit.SECONDS).getBreakpoints().get(0).getLine());
			adapter.continue_(new ContinueArguments().setThreadId(1)).get(10, TimeUnit.SECONDS);
			StoppedEventBody stop = client.stops.poll(10, TimeUnit.SECONDS);
			assertNotNull(stop);
			// the reverse request goes through the NIO side too
			assertEquals(4242, adapter.threads().get(10, TimeUnit.SECONDS).getThreads().get(0).getId());
			c.close();
		} finally {
			server.stop();
		}
	}

	@Test
	public void nioClientWithAStreamAdapter() throws Exception {
		try(ServerSocket ss = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
				NioClient nio = new NioClient("dap-test-client")) {
			Thread acceptor = new Thread(()->{
				try {
					Socket s = ss.accept();
					DapConnection.adapter(TestConnection.Adapter::new, s.getInputStream(), s.getOutputStream());
				} catch (Exception e) {
					// the test fails on its own
				}
			});
			acceptor.setDaemon(true);
			acceptor.start();
			TestConnection.Client client = new TestConnection.Client();
			DapNioHandler handler = DapNioHandler.client(client);
			nio.setHandlerExecutor(Executors.newCachedThreadPool());
			nio.connectAndWait(new InetSocketAddress(InetAddress.getLoopbackAddress(), ss.getLocalPort()), handler,
					new ContentLengthFrameDecoder());
			IDebugAdapter adapter = handler.getAdapter();
			adapter.initialize(new InitializeRequestArguments().setAdapterID("t")).get(10, TimeUnit.SECONDS);
			client.initialized.get(10, TimeUnit.SECONDS);
			adapter.continue_(new ContinueArguments().setThreadId(1)).get(10, TimeUnit.SECONDS);
			assertNotNull(client.stops.poll(10, TimeUnit.SECONDS));
			assertEquals(4242, adapter.threads().get(10, TimeUnit.SECONDS).getThreads().get(0).getId());
		}
	}
}
