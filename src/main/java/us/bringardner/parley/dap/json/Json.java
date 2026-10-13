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
package us.bringardner.parley.dap.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON (RFC 8259) reader and writer, with no dependencies.
 * <p>
 * JSON values are read as plain Java objects:
 * <table>
 * <caption>JSON to Java</caption>
 * <tr><td>object</td><td>{@code Map<String,Object>} (a LinkedHashMap: members keep their order)</td></tr>
 * <tr><td>array</td><td>{@code List<Object>} (an ArrayList)</td></tr>
 * <tr><td>string</td><td>String</td></tr>
 * <tr><td>number</td><td>Long if it's an integer that fits, else BigInteger for a bigger integer, else Double</td></tr>
 * <tr><td>true, false</td><td>Boolean</td></tr>
 * <tr><td>null</td><td>null</td></tr>
 * </table>
 * Writing takes the same types, plus any Number, arrays, other Collections and Maps, and
 * {@link JsonValue}s (written as what their toJson() returns).
 */
public final class Json {

	/** Deepest nesting accepted when reading (a guard against stack overflow on hostile input). */
	public static final int MAX_DEPTH = 512;

	private Json() {
	}

	// ------------------------------------------------------------------ reading

	/**
	 * Reads one JSON value; nothing but white space may follow it.
	 * @throws JsonException if text isn't valid JSON
	 */
	public static Object parse(String text) throws JsonException {
		Parser p = new Parser(text);
		p.skipSpace();
		Object ret = p.value(0);
		p.skipSpace();
		if( p.pos < text.length()) {
			throw p.error("Unexpected text after the JSON value");
		}
		return ret;
	}

	/** Reads a JSON object. @throws JsonException if text isn't a valid JSON object */
	@SuppressWarnings("unchecked")
	public static Map<String,Object> parseObject(String text) throws JsonException {
		Object o = parse(text);
		if( !(o instanceof Map)) {
			throw new JsonException("Not a JSON object", 0);
		}
		return (Map<String,Object>) o;
	}

	private static final class Parser {
		final String s;
		int pos;

		Parser(String s) {
			this.s = s;
		}

		JsonException error(String msg) {
			return new JsonException(msg+" at position "+pos, pos);
		}

		void skipSpace() {
			while( pos < s.length()) {
				char c = s.charAt(pos);
				if( c == ' ' || c == '\t' || c == '\n' || c == '\r') {
					pos++;
				} else {
					break;
				}
			}
		}

		Object value(int depth) throws JsonException {
			if( depth > MAX_DEPTH ) {
				throw error("JSON nested too deeply");
			}
			if( pos >= s.length()) {
				throw error("Unexpected end of JSON");
			}
			char c = s.charAt(pos);
			switch (c) {
			case '{': return object(depth);
			case '[': return array(depth);
			case '"': return string();
			case 't': literal("true"); return Boolean.TRUE;
			case 'f': literal("false"); return Boolean.FALSE;
			case 'n': literal("null"); return null;
			default:
				if( c == '-' || (c >= '0' && c <= '9')) {
					return number();
				}
				throw error("Unexpected character '"+c+"'");
			}
		}

		void literal(String word) throws JsonException {
			if( !s.startsWith(word, pos)) {
				throw error("Expected "+word);
			}
			pos += word.length();
		}

		Map<String,Object> object(int depth) throws JsonException {
			Map<String,Object> ret = new LinkedHashMap<>();
			pos++;
			skipSpace();
			if( pos < s.length() && s.charAt(pos) == '}') {
				pos++;
				return ret;
			}
			while( true ) {
				skipSpace();
				if( pos >= s.length() || s.charAt(pos) != '"') {
					throw error("Expected a member name");
				}
				String name = string();
				skipSpace();
				if( pos >= s.length() || s.charAt(pos) != ':') {
					throw error("Expected ':'");
				}
				pos++;
				skipSpace();
				ret.put(name, value(depth+1));
				skipSpace();
				if( pos >= s.length()) {
					throw error("Unexpected end of JSON in an object");
				}
				char c = s.charAt(pos++);
				if( c == '}') {
					return ret;
				}
				if( c != ',') {
					pos--;
					throw error("Expected ',' or '}'");
				}
			}
		}

		List<Object> array(int depth) throws JsonException {
			List<Object> ret = new ArrayList<>();
			pos++;
			skipSpace();
			if( pos < s.length() && s.charAt(pos) == ']') {
				pos++;
				return ret;
			}
			while( true ) {
				skipSpace();
				ret.add(value(depth+1));
				skipSpace();
				if( pos >= s.length()) {
					throw error("Unexpected end of JSON in an array");
				}
				char c = s.charAt(pos++);
				if( c == ']') {
					return ret;
				}
				if( c != ',') {
					pos--;
					throw error("Expected ',' or ']'");
				}
			}
		}

		String string() throws JsonException {
			pos++;
			StringBuilder b = null;
			int start = pos;
			while( true ) {
				if( pos >= s.length()) {
					throw error("Unterminated string");
				}
				char c = s.charAt(pos);
				if( c == '"') {
					String ret = b == null ? s.substring(start, pos) : b.append(s, start, pos).toString();
					pos++;
					return ret;
				}
				if( c < 0x20 ) {
					throw error("Control character in a string");
				}
				if( c == '\\') {
					if( b == null ) {
						b = new StringBuilder();
					}
					b.append(s, start, pos);
					pos++;
					if( pos >= s.length()) {
						throw error("Unterminated escape");
					}
					char e = s.charAt(pos++);
					switch (e) {
					case '"': b.append('"'); break;
					case '\\': b.append('\\'); break;
					case '/': b.append('/'); break;
					case 'b': b.append('\b'); break;
					case 'f': b.append('\f'); break;
					case 'n': b.append('\n'); break;
					case 'r': b.append('\r'); break;
					case 't': b.append('\t'); break;
					case 'u':
						if( pos+4 > s.length()) {
							throw error("Bad \\u escape");
						}
						int v = 0;
						for(int i=0; i < 4; i++) {
							int d = Character.digit(s.charAt(pos+i), 16);
							if( d < 0 ) {
								throw error("Bad \\u escape");
							}
							v = v*16+d;
						}
						b.append((char) v);
						pos += 4;
						break;
					default:
						pos--;
						throw error("Bad escape \\"+e);
					}
					start = pos;
				} else {
					pos++;
				}
			}
		}

		Object number() throws JsonException {
			int start = pos;
			if( s.charAt(pos) == '-') {
				pos++;
			}
			if( pos >= s.length()) {
				throw error("Bad number");
			}
			if( s.charAt(pos) == '0') {
				pos++;
			} else if( s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
				while( pos < s.length() && Character.isDigit(s.charAt(pos))) {
					pos++;
				}
			} else {
				throw error("Bad number");
			}
			boolean integer = true;
			if( pos < s.length() && s.charAt(pos) == '.') {
				integer = false;
				pos++;
				int digits = pos;
				while( pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
					pos++;
				}
				if( pos == digits ) {
					throw error("Bad number");
				}
			}
			if( pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
				integer = false;
				pos++;
				if( pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
					pos++;
				}
				int digits = pos;
				while( pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
					pos++;
				}
				if( pos == digits ) {
					throw error("Bad number");
				}
			}
			String text = s.substring(start, pos);
			if( integer ) {
				if( text.length() < 19 ) {
					return Long.parseLong(text);
				}
				BigInteger big = new BigInteger(text);
				return big.bitLength() < 64 ? (Object) big.longValue() : big;
			}
			return Double.parseDouble(text);
		}
	}

	// ------------------------------------------------------------------ writing

	/**
	 * Writes a value as compact JSON.
	 * @throws IllegalArgumentException for a value that has no JSON form (NaN, an unknown type ...)
	 */
	public static String write(Object value) {
		StringBuilder b = new StringBuilder();
		write(b, value, 0);
		return b.toString();
	}

	private static void write(StringBuilder b, Object value, int depth) {
		if( depth > MAX_DEPTH ) {
			throw new IllegalArgumentException("Value nested too deeply (a cycle?)");
		}
		if( value == null ) {
			b.append("null");
		} else if( value instanceof JsonValue ) {
			write(b, ((JsonValue) value).toJson(), depth+1);
		} else if( value instanceof CharSequence || value instanceof Character || value instanceof Enum ) {
			quote(b, value.toString());
		} else if( value instanceof Boolean ) {
			b.append(value.toString());
		} else if( value instanceof Double || value instanceof Float ) {
			double d = ((Number) value).doubleValue();
			if( Double.isNaN(d) || Double.isInfinite(d)) {
				throw new IllegalArgumentException("JSON has no "+d);
			}
			if( d == Math.rint(d) && Math.abs(d) < 1e15 ) {
				b.append((long) d);
			} else {
				b.append(value.toString());
			}
		} else if( value instanceof BigDecimal ) {
			b.append(((BigDecimal) value).toString());
		} else if( value instanceof Number ) {
			b.append(value.toString());
		} else if( value instanceof Map ) {
			b.append('{');
			boolean first = true;
			for(Map.Entry<?,?> e : ((Map<?,?>) value).entrySet()) {
				if( !first ) {
					b.append(',');
				}
				first = false;
				quote(b, String.valueOf(e.getKey()));
				b.append(':');
				write(b, e.getValue(), depth+1);
			}
			b.append('}');
		} else if( value instanceof Collection ) {
			b.append('[');
			boolean first = true;
			for(Object o : (Collection<?>) value) {
				if( !first ) {
					b.append(',');
				}
				first = false;
				write(b, o, depth+1);
			}
			b.append(']');
		} else if( value.getClass().isArray()) {
			b.append('[');
			int len = java.lang.reflect.Array.getLength(value);
			for(int i=0; i < len; i++) {
				if( i > 0 ) {
					b.append(',');
				}
				write(b, java.lang.reflect.Array.get(value, i), depth+1);
			}
			b.append(']');
		} else {
			throw new IllegalArgumentException("No JSON form for "+value.getClass().getName());
		}
	}

	/** Appends s as a JSON string. */
	public static void quote(StringBuilder b, String s) {
		b.append('"');
		for(int i=0, n=s.length(); i < n; i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"': b.append("\\\""); break;
			case '\\': b.append("\\\\"); break;
			case '\n': b.append("\\n"); break;
			case '\r': b.append("\\r"); break;
			case '\t': b.append("\\t"); break;
			case '\b': b.append("\\b"); break;
			case '\f': b.append("\\f"); break;
			default:
				// control characters, and the line separators JavaScript can't take in a string
				if( c < 0x20 || c == ' ' || c == ' ') {
					b.append(String.format("\\u%04x", (int) c));
				} else {
					b.append(c);
				}
			}
		}
		b.append('"');
	}
}
