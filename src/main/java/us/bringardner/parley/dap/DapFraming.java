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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * DAP's base protocol on streams: each message is a header part (lines of "Name: value",
 * of which Content-Length is required) and a blank line, followed by Content-Length bytes of
 * UTF-8 JSON.
 * <pre>
 * Content-Length: 119\r\n
 * \r\n
 * {"seq":153,"type":"request","command":"next", ...}
 * </pre>
 */
public final class DapFraming {

	/** Largest message accepted (bytes of JSON). */
	public static final int DEFAULT_MAX_MESSAGE = 64*1024*1024;
	/** Longest header line accepted. */
	public static final int MAX_HEADER_LINE = 8192;

	private DapFraming() {
	}

	/**
	 * Reads one message.
	 * @return its JSON text, or null at the end of the stream (between messages)
	 * @throws IOException if the stream ends inside a message or the header is bad
	 */
	public static String read(InputStream in) throws IOException {
		return read(in, DEFAULT_MAX_MESSAGE);
	}

	public static String read(InputStream in, int maxMessage) throws IOException {
		int length = -1;
		boolean any = false;
		while( true ) {
			String line = readLine(in, !any);
			if( line == null ) {
				return null;
			}
			any = true;
			if( line.isEmpty()) {
				break;
			}
			int colon = line.indexOf(':');
			if( colon < 0 ) {
				throw new IOException("Bad DAP header line: "+line);
			}
			String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			if( name.equals("content-length")) {
				try {
					length = Integer.parseInt(line.substring(colon+1).trim());
				} catch (NumberFormatException e) {
					throw new IOException("Bad Content-Length: "+line);
				}
			}
			// other headers (Content-Type) are allowed and ignored
		}
		if( length < 0 ) {
			throw new IOException("DAP message without Content-Length");
		}
		if( length > maxMessage ) {
			throw new IOException("DAP message of "+length+" bytes is larger than "+maxMessage);
		}
		byte[] body = in.readNBytes(length);
		if( body.length < length ) {
			throw new EOFException("The stream ended inside a DAP message");
		}
		return new String(body, StandardCharsets.UTF_8);
	}

	/** A header line without its line end (CR LF, or LF alone); null at the end before any byte if allowed. */
	private static String readLine(InputStream in, boolean eofAllowed) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream(64);
		while( true ) {
			int c = in.read();
			if( c < 0 ) {
				if( eofAllowed && b.size() == 0 ) {
					return null;
				}
				throw new EOFException("The stream ended inside a DAP header");
			}
			if( c == '\n') {
				byte[] bytes = b.toByteArray();
				int len = bytes.length;
				if( len > 0 && bytes[len-1] == '\r') {
					len--;
				}
				return new String(bytes, 0, len, StandardCharsets.US_ASCII);
			}
			if( b.size() >= MAX_HEADER_LINE ) {
				throw new IOException("DAP header line too long");
			}
			b.write(c);
		}
	}

	/** The bytes of a message: header and JSON. */
	public static byte[] frame(String json) {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		byte[] header = ("Content-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
		byte[] ret = new byte[header.length+body.length];
		System.arraycopy(header, 0, ret, 0, header.length);
		System.arraycopy(body, 0, ret, header.length, body.length);
		return ret;
	}

	/** Writes one message and flushes. */
	public static void write(OutputStream out, String json) throws IOException {
		out.write(frame(json));
		out.flush();
	}
}
