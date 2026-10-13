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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class TestJson {

	@Test
	public void readsEveryKindOfValue() throws Exception {
		Object o = Json.parse(" {\"s\":\"a\\\"b\\\\c\\/\\n\\u00e9\", \"i\": -42, \"big\": 123456789012345678901234,"
				+ " \"long\": 9007199254740993, \"d\": 1.5e3, \"t\": true, \"f\": false, \"n\": null,"
				+ " \"a\": [1, [2], {}], \"o\": {\"x\": []}} ");
		Map<?,?> m = (Map<?,?>) o;
		assertEquals("a\"b\\c/\né", m.get("s"));
		assertEquals(-42L, m.get("i"));
		assertEquals(new BigInteger("123456789012345678901234"), m.get("big"));
		assertEquals(9007199254740993L, m.get("long"));
		assertEquals(1500.0, m.get("d"));
		assertEquals(Boolean.TRUE, m.get("t"));
		assertEquals(Boolean.FALSE, m.get("f"));
		assertTrue(m.containsKey("n"));
		assertNull(m.get("n"));
		assertEquals(Arrays.asList(1L, Arrays.asList(2L), new LinkedHashMap<>()), m.get("a"));
		// members keep their order
		assertEquals(Arrays.asList("s", "i", "big", "long", "d", "t", "f", "n", "a", "o"), List.copyOf(m.keySet()));
	}

	@Test
	public void writesWhatItReads() throws Exception {
		String text = "{\"a\":[1,2.5,\"x\\ty\",true,null,{\"b\":{}}],\"c\":\"\\u0001\\u2028\"}";
		assertEquals(text, Json.write(Json.parse(text)));
		assertEquals("3", Json.write(3.0));
		assertEquals("[1,2]", Json.write(new int[] {1, 2}));
		assertEquals("\"\uD83D\uDE00\"", Json.write("\uD83D\uDE00"));
		assertEquals("\uD83D\uDE00", Json.parse("\"\\ud83d\\ude00\""));
	}

	@Test
	public void rejectsBadJson() {
		for(String bad : new String[] {"", "{", "{\"a\" 1}", "[1,]", "[1 2]", "\"abc", "01", "1.", "-", "tru",
				"{\"a\":1}x", "\"\\x\"", "\"a\nb\"", "{1:2}", "nul"}) {
			assertThrows(JsonException.class, ()->Json.parse(bad), bad);
		}
		StringBuilder deep = new StringBuilder();
		for(int i=0; i < Json.MAX_DEPTH+10; i++) {
			deep.append('[');
		}
		assertThrows(JsonException.class, ()->Json.parse(deep.toString()));
		assertThrows(IllegalArgumentException.class, ()->Json.write(Double.NaN));
		assertThrows(IllegalArgumentException.class, ()->Json.write(new Object()));
	}

	@Test
	public void conversions() throws Exception {
		assertEquals(3, JsonValues.integer(3.0, "x"));
		assertThrows(JsonException.class, ()->JsonValues.integer(3.5, "x"));
		assertThrows(JsonException.class, ()->JsonValues.string(1L, "x"));
		assertEquals(Arrays.asList(1, 2), JsonValues.list(Arrays.asList(1L, 2L), "x", JsonValues::integer));
		JsonException e = assertThrows(JsonException.class, ()->JsonValues.list(Arrays.asList(1L, "a"), "ids", JsonValues::integer));
		assertTrue(e.getMessage().startsWith("ids[1]"), e.getMessage());
	}
}
