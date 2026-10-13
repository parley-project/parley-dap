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
import java.util.Locale;

import us.bringardner.parley.dap.DapFraming;
import us.bringardner.parley.net.nio.IFrameDecoder;

/**
 * Splits non-blocking input into DAP messages: a header part ending with a blank line, with a
 * Content-Length, then that many bytes. Each frame is a message's JSON (UTF-8), without the
 * header.
 */
public class ContentLengthFrameDecoder implements IFrameDecoder {

	private final int maxMessage;

	public ContentLengthFrameDecoder() {
		this(DapFraming.DEFAULT_MAX_MESSAGE);
	}

	public ContentLengthFrameDecoder(int maxMessage) {
		this.maxMessage = maxMessage;
	}

	@Override
	public ByteBuffer decode(ByteBuffer in) throws IOException {
		int start = in.position();
		int limit = in.limit();
		// find the blank line that ends the header (CR LF CR LF, or LF LF)
		int headerEnd = -1;
		int bodyStart = -1;
		for(int i=start; i < limit; i++) {
			if( in.get(i) == '\n') {
				if( i+1 < limit && in.get(i+1) == '\n') {
					headerEnd = i;
					bodyStart = i+2;
					break;
				}
				if( i+2 < limit && in.get(i+1) == '\r' && in.get(i+2) == '\n') {
					headerEnd = i;
					bodyStart = i+3;
					break;
				}
			}
			if( i-start > 64*1024 ) {
				throw new IOException("DAP header too long");
			}
		}
		if( headerEnd < 0 ) {
			return null;
		}
		byte[] header = new byte[headerEnd-start];
		for(int i=0; i < header.length; i++) {
			header[i] = in.get(start+i);
		}
		int length = -1;
		for(String line : new String(header, StandardCharsets.US_ASCII).split("\n")) {
			line = line.trim();
			if( line.isEmpty()) {
				continue;
			}
			int colon = line.indexOf(':');
			if( colon < 0 ) {
				throw new IOException("Bad DAP header line: "+line);
			}
			if( line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("content-length")) {
				try {
					length = Integer.parseInt(line.substring(colon+1).trim());
				} catch (NumberFormatException e) {
					throw new IOException("Bad Content-Length: "+line);
				}
			}
		}
		if( length < 0 ) {
			throw new IOException("DAP message without Content-Length");
		}
		if( length > maxMessage ) {
			throw new IOException("DAP message of "+length+" bytes is larger than "+maxMessage);
		}
		if( limit-bodyStart < length ) {
			return null;
		}
		in.position(bodyStart);
		return IFrameDecoder.copy(in, length);
	}
}
