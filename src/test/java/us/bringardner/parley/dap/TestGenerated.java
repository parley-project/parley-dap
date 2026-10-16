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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.dap.generator.DapGenerator;
import us.bringardner.parley.dap.json.Json;
import us.bringardner.parley.dap.json.JsonException;
import us.bringardner.parley.dap.protocol.Breakpoint;
import us.bringardner.parley.dap.protocol.LaunchRequestArguments;
import us.bringardner.parley.dap.protocol.Source;
import us.bringardner.parley.dap.protocol.StackFrameFormat;
import us.bringardner.parley.dap.protocol.StoppedEventBody;
import us.bringardner.parley.dap.protocol.SteppingGranularity;

/** The generated protocol classes: up to date with the schema, and their JSON. */
public class TestGenerated {

	/**
	 * The project folder (the one with pom.xml), found by walking up from where the test classes are,
	 * so the check works from any working directory and whatever the build's output folder is called.
	 */
	private static Path projectDir() throws Exception {
		Path dir = Paths.get(TestGenerated.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		for(Path d = dir; d != null; d = d.getParent()) {
			if( Files.isRegularFile(d.resolve("pom.xml")) ) {
				return d;
			}
		}
		throw new IllegalStateException("no pom.xml above "+dir);
	}

	@Test
	public void generatedSourcesMatchTheSchema() throws Exception {
		Path project = projectDir();
		Map<String,String> want = DapGenerator.generate(Files.readString(project.resolve("schema/debugAdapterProtocol.json")));
		Path dir = project.resolve("src/main/java/us/bringardner/parley/dap/protocol");
		Set<String> have = new TreeSet<>();
		try(java.util.stream.Stream<Path> s = Files.list(dir)) {
			s.forEach(p -> have.add(p.getFileName().toString()));
		}
		assertEquals(new TreeSet<>(want.keySet()), have, "run DapGenerator: the set of classes differs");
		for(Map.Entry<String,String> e : want.entrySet()) {
			assertEquals(e.getValue(), Files.readString(dir.resolve(e.getKey()), StandardCharsets.UTF_8),
					e.getKey()+" differs from what DapGenerator makes: regenerate (don't edit generated classes)");
		}
	}

	@Test
	public void toAndFromJson() throws Exception {
		Breakpoint b = new Breakpoint().setId(3).setVerified(true).setLine(12)
				.setSource(new Source().setPath("/x/a.sh").setName("a.sh"));
		String json = Json.write(b);
		assertEquals("{\"id\":3,\"verified\":true,\"source\":{\"name\":\"a.sh\",\"path\":\"/x/a.sh\"},\"line\":12}", json);
		Breakpoint back = Breakpoint.fromJson(Json.parse(json));
		assertEquals(b, back);
		assertEquals("/x/a.sh", back.getSource().getPath());

		StoppedEventBody s = StoppedEventBody.fromJson(Json.parse("{\"reason\":\"breakpoint\",\"threadId\":1,\"hitBreakpointIds\":[4,5]}"));
		assertEquals(StoppedEventBody.Reason.BREAKPOINT, s.getReason());
		assertEquals(List.of(4, 5), s.getHitBreakpointIds());
		assertEquals("statement", SteppingGranularity.STATEMENT);
	}

	@Test
	public void unknownMembersAreKept() throws Exception {
		// launch arguments are the adapter's own, apart from noDebug and __restart
		LaunchRequestArguments a = LaunchRequestArguments.fromJson(Json.parse(
				"{\"noDebug\":true,\"program\":\"/x/a.sh\",\"args\":[\"1\"],\"__restart\":{\"n\":1}}"));
		assertTrue(a.getNoDebug());
		assertEquals("/x/a.sh", a.getExtra().get("program"));
		assertEquals(Map.of("n", 1L), a.getRestart());
		assertEquals("{\"noDebug\":true,\"__restart\":{\"n\":1},\"program\":\"/x/a.sh\",\"args\":[\"1\"]}", Json.write(a));
	}

	@Test
	public void subclassesReadTheirParentsMembers() throws Exception {
		StackFrameFormat f = StackFrameFormat.fromJson(Json.parse("{\"hex\":true,\"line\":true,\"other\":1}"));
		assertTrue(f.getHex());
		assertTrue(f.getLine());
		assertEquals(Map.of("other", 1L), f.getExtra());
		assertNull(f.getModule());
	}

	@Test
	public void wrongTypesAreReported() {
		JsonException e = assertThrows(JsonException.class, ()->Breakpoint.fromJson(Json.parse("{\"line\":\"twelve\"}")));
		assertTrue(e.getMessage().contains("Breakpoint.line"), e.getMessage());
		assertThrows(JsonException.class, ()->Breakpoint.fromJson("not an object"));
	}
}
