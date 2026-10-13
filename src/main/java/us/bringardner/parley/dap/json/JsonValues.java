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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Conversions between parsed JSON values and Java types, used by the generated DAP classes.
 * Reading is lenient where JSON allows it to be (a number may come as 3 or 3.0) and strict
 * where a wrong type would be a mistake (an object where a string is expected).
 */
public final class JsonValues {

	private JsonValues() {
	}

	/** value as a JSON object. @throws JsonException if it's something else */
	@SuppressWarnings("unchecked")
	public static Map<String,Object> object(Object value, String what) throws JsonException {
		if( value instanceof Map ) {
			return (Map<String,Object>) value;
		}
		throw new JsonException(what+" must be a JSON object, not "+describe(value));
	}

	public static String string(Object value, String what) throws JsonException {
		if( value == null || value instanceof String ) {
			return (String) value;
		}
		throw new JsonException(what+" must be a string, not "+describe(value));
	}

	public static Integer integer(Object value, String what) throws JsonException {
		if( value == null ) {
			return null;
		}
		if( value instanceof Number ) {
			Number n = (Number) value;
			double d = n.doubleValue();
			if( d != Math.rint(d) || d > Integer.MAX_VALUE || d < Integer.MIN_VALUE ) {
				throw new JsonException(what+" must be an integer, not "+value);
			}
			return n.intValue();
		}
		throw new JsonException(what+" must be an integer, not "+describe(value));
	}

	public static Double number(Object value, String what) throws JsonException {
		if( value == null ) {
			return null;
		}
		if( value instanceof Number ) {
			return ((Number) value).doubleValue();
		}
		throw new JsonException(what+" must be a number, not "+describe(value));
	}

	public static Boolean bool(Object value, String what) throws JsonException {
		if( value == null || value instanceof Boolean ) {
			return (Boolean) value;
		}
		throw new JsonException(what+" must be true or false, not "+describe(value));
	}

	/** A converter from a JSON value to T, which may fail. */
	public interface Reader<T> {
		T read(Object value, String what) throws JsonException;
	}

	public static <T> List<T> list(Object value, String what, Reader<T> item) throws JsonException {
		if( value == null ) {
			return null;
		}
		if( !(value instanceof List)) {
			throw new JsonException(what+" must be an array, not "+describe(value));
		}
		List<?> in = (List<?>) value;
		List<T> ret = new ArrayList<>(in.size());
		for(int i=0; i < in.size(); i++) {
			ret.add(item.read(in.get(i), what+"["+i+"]"));
		}
		return ret;
	}

	public static <T> Map<String,T> map(Object value, String what, Reader<T> item) throws JsonException {
		if( value == null ) {
			return null;
		}
		Map<String,Object> in = object(value, what);
		Map<String,T> ret = new LinkedHashMap<>();
		for(Map.Entry<String,Object> e : in.entrySet()) {
			ret.put(e.getKey(), item.read(e.getValue(), what+"."+e.getKey()));
		}
		return ret;
	}

	/** Any JSON value, as it is. */
	public static Object any(Object value, String what) {
		return value;
	}

	/** Writes a list of items as a JSON array (null stays null). */
	public static <T> List<Object> toJson(List<T> list, Function<T,Object> item) {
		if( list == null ) {
			return null;
		}
		List<Object> ret = new ArrayList<>(list.size());
		for(T t : list) {
			ret.add(item.apply(t));
		}
		return ret;
	}

	/** Writes a map's values (null stays null). */
	public static <T> Map<String,Object> toJson(Map<String,T> map, Function<T,Object> item) {
		if( map == null ) {
			return null;
		}
		Map<String,Object> ret = new LinkedHashMap<>();
		for(Map.Entry<String,T> e : map.entrySet()) {
			ret.put(e.getKey(), item.apply(e.getValue()));
		}
		return ret;
	}

	/** A JsonValue's JSON form, or null. */
	public static Object toJson(JsonValue v) {
		return v == null ? null : v.toJson();
	}

	/** Puts a member if the value isn't null (DAP leaves optional members out). */
	public static void put(Map<String,Object> json, String name, Object value) {
		if( value != null ) {
			json.put(name, value);
		}
	}

	static String describe(Object value) {
		if( value == null ) {
			return "null";
		}
		if( value instanceof Map ) {
			return "an object";
		}
		if( value instanceof List ) {
			return "an array";
		}
		if( value instanceof String ) {
			return "a string";
		}
		if( value instanceof Boolean ) {
			return value.toString();
		}
		return value.toString();
	}
}
