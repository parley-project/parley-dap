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

import us.bringardner.parley.dap.protocol.Message;

/**
 * A request that failed: the other end answered with success false, or a request handler
 * throws it (or fails its future with it) to answer that way.
 * <p>
 * The message is the error response's short message (DAP suggests, for example,
 * "cancelled" or "notStopped"); the optional Message is its structured error, which a
 * client may show to the user.
 */
public class DapException extends RuntimeException {

	private static final long serialVersionUID = 1L;
	private final transient Message error;

	public DapException(String message) {
		this(message, null);
	}

	/**
	 * @param error the error to show (its format may use {name} variables), or null
	 */
	public DapException(String message, Message error) {
		super(message);
		this.error = error;
	}

	/** An error the user should see: the response's message and a Message with showUser set. */
	public static DapException forUser(String text) {
		Message m = new Message();
		m.setId(1);
		m.setFormat(text);
		m.setShowUser(true);
		return new DapException(text, m);
	}

	/** The structured error from the response, or null. */
	public Message getError() {
		return error;
	}

	/** The text to show: the structured error's format (variables filled in), else the message. */
	public String getText() {
		if( error != null && error.getFormat() != null ) {
			String text = error.getFormat();
			if( error.getVariables() != null ) {
				for(java.util.Map.Entry<String,String> e : error.getVariables().entrySet()) {
					text = text.replace("{"+e.getKey()+"}", String.valueOf(e.getValue()));
				}
			}
			return text;
		}
		return getMessage();
	}
}
