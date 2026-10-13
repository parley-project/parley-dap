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

import java.io.IOException;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

	@Test
	public void rejectsHugeNumbers() throws Exception {
		String digits = "9".repeat(Json.MAX_NUMBER_LENGTH+1);
		long start = System.nanoTime();
		assertThrows(JsonException.class, ()->Json.parse(digits));
		assertThrows(JsonException.class, ()->Json.parse("1"+"0".repeat(1_000_000)));
		assertThrows(JsonException.class, ()->Json.parse("0."+"0".repeat(Json.MAX_NUMBER_LENGTH)));
		assertTrue(System.nanoTime()-start < 2_000_000_000L, "should fail fast");
		assertEquals(new BigInteger("9".repeat(Json.MAX_NUMBER_LENGTH)), Json.parse("9".repeat(Json.MAX_NUMBER_LENGTH)));
	}

	@Test
	public void rejectsNumbersOutOfRange() {
		for(String bad : new String[] {"1e400", "-1e400", "[1E999]", "{\"a\":1e309}"}) {
			assertThrows(JsonException.class, ()->Json.parse(bad), bad);
		}
	}

	@Test
	public void rejectsRepeatedMemberNames() throws Exception {
		JsonException e = assertThrows(JsonException.class, ()->Json.parse("{\"a\":1,\"a\":2}"));
		assertEquals(7, e.getPosition());
		assertThrows(JsonException.class, ()->Json.parse("{\"a\":{\"b\":1,\"c\":2,\"b\":null}}"));
		// the same name in different objects is fine
		assertEquals(2, ((Map<?,?>) Json.parse("{\"a\":{\"x\":1},\"b\":{\"x\":2}}")).size());
	}

	@Test
	public void writeRejectsNullMemberName() {
		Map<String,Object> m = new LinkedHashMap<>();
		m.put(null, 1);
		assertThrows(IllegalArgumentException.class, ()->Json.write(m));
	}

	@Test
	public void writeEscapesLoneSurrogates() throws Exception {
		assertEquals("\"\\ud800\"", Json.write("\uD800"));
		assertEquals("\"\\udc00\"", Json.write("\uDC00"));
		assertEquals("\"a\\udc00\\ud800b\"", Json.write("a\uDC00\uD800b"));
		assertEquals("\"\uD83D\uDE00\"", Json.write("\uD83D\uDE00"));
		// and what's written reads back
		assertEquals("a\uDC00\uD800b", Json.parse(Json.write("a\uDC00\uD800b")));
	}

	@Test
	public void acceptsOnlyAsciiDigits() {
		for(String bad : new String[] {"1\u0663", "\u0663", "-\u0663", "1.\u0663", "1e\u0663", "\"\\u\u0663\u0663\u0663\u0663\"",
				"\"\\u00\u0665\u0665\"", "\"\\u\uff11\uff11\uff11\uff11\""}) {
			assertThrows(JsonException.class, ()->Json.parse(bad), bad);
		}
	}

	@Test
	public void errorsHaveLineAndColumn() {
		JsonException e = assertThrows(JsonException.class, ()->Json.parse("{\n  \"a\": 1,\n  \"b\" 2\n}"));
		assertEquals(3, e.getLine());
		assertEquals(7, e.getColumn());
		assertEquals(e.getPosition(), "{\n  \"a\": 1,\n  \"b\" ".length());
		assertTrue(e.getMessage().contains("line 3, column 7"), e.getMessage());
		e = assertThrows(JsonException.class, ()->Json.parse("[1,]"));
		assertEquals(1, e.getLine());
		assertEquals(4, e.getColumn());
		assertEquals(-1, new JsonException("x").getLine());
	}

	/** nst/JSONTestSuite: y_ must parse, n_ must be a JsonException, i_ (implementation defined) mustn't blow up. */
	@Test
	public void jsonTestSuite() throws IOException, URISyntaxException {
		Path dir = Paths.get(getClass().getResource("/JSONTestSuite/test_parsing").toURI());
		// Valid JSON, but this reader rejects repeated member names on purpose.
		List<String> repeatedKeys = Arrays.asList("y_object_duplicated_key.json", "y_object_duplicated_key_and_value.json");
		List<String> failures = new ArrayList<>();
		int count = 0;
		try (var files = Files.list(dir)) {
			for(Path f : (Iterable<Path>) files.sorted()::iterator) {
				String name = f.getFileName().toString();
				if( !name.endsWith(".json")) {
					continue;
				}
				count++;
				String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
				try {
					Json.parse(text);
					if( name.startsWith("n_") || repeatedKeys.contains(name)) {
						failures.add(name+" was accepted");
					}
				} catch (JsonException e) {
					if( name.startsWith("y_") && !repeatedKeys.contains(name)) {
						failures.add(name+" was rejected: "+e.getMessage());
					}
				} catch (RuntimeException | StackOverflowError e) {
					failures.add(name+" threw "+e);
				}
			}
		}
		assertTrue(count > 300, "only found "+count+" files");
		assertTrue(failures.isEmpty(), failures.toString());
	}
}
