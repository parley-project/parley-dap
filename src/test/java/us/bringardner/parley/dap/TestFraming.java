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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.dap.nio.ContentLengthFrameDecoder;

public class TestFraming {

	private static ByteArrayInputStream in(String s) {
		return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void writesAndReadsMessages() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		DapFraming.write(out, "{\"a\":\"é\"}");
		DapFraming.write(out, "{}");
		assertEquals("Content-Length: 10\r\n\r\n{\"a\":\"é\"}Content-Length: 2\r\n\r\n{}", out.toString(StandardCharsets.UTF_8));
		ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
		assertEquals("{\"a\":\"é\"}", DapFraming.read(in));
		assertEquals("{}", DapFraming.read(in));
		assertNull(DapFraming.read(in));
	}

	@Test
	public void headersMayVary() throws IOException {
		assertEquals("{}", DapFraming.read(in("content-length:2\nContent-Type: application/vscode-jsonrpc; charset=utf-8\n\n{}")));
	}

	@Test
	public void badInput() {
		assertThrows(IOException.class, ()->DapFraming.read(in("Content-Type: x\r\n\r\n{}")));
		assertThrows(IOException.class, ()->DapFraming.read(in("Content-Length: x\r\n\r\n{}")));
		assertThrows(EOFException.class, ()->DapFraming.read(in("Content-Length: 5\r\n\r\n{}")));
		assertThrows(EOFException.class, ()->DapFraming.read(in("Content-Len")));
		assertThrows(IOException.class, ()->DapFraming.read(in("Content-Length: 99\r\n\r\n{}"), 10));
	}

	@Test
	public void nonBlockingDecoderWaitsForWholeMessages() throws IOException {
		byte[] two = (new String(DapFraming.frame("{\"x\":1}"), StandardCharsets.UTF_8)
				+new String(DapFraming.frame("[]"), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
		ContentLengthFrameDecoder d = new ContentLengthFrameDecoder();
		// byte by byte, as a slow network might deliver it
		ByteBuffer buf = ByteBuffer.allocate(two.length);
		StringBuilder frames = new StringBuilder();
		for(byte b : two) {
			buf.put(b);
			buf.flip();
			ByteBuffer f;
			while( (f = d.decode(buf)) != null ) {
				frames.append(StandardCharsets.UTF_8.decode(f)).append('|');
			}
			buf.compact();
		}
		assertEquals("{\"x\":1}|[]|", frames.toString());
		assertThrows(IOException.class, ()->new ContentLengthFrameDecoder(5).decode(ByteBuffer.wrap(DapFraming.frame("{\"long\":1}"))));
	}
}
