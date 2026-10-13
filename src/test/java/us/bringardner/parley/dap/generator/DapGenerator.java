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
package us.bringardner.parley.dap.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import us.bringardner.parley.dap.json.Json;

/**
 * Generates parley-dap's protocol classes (package us.bringardner.parley.dap.protocol) from
 * Microsoft's JSON schema of the Debug Adapter Protocol (schema/debugAdapterProtocol.json).
 * <pre>
 * java -cp target/classes:target/test-classes us.bringardner.parley.dap.generator.DapGenerator \
 *      schema/debugAdapterProtocol.json src/main/java
 * </pre>
 * What it makes:
 * <ul>
 * <li>a class for each type (Capabilities, Source, Breakpoint, the *Arguments ...) and for
 *     each response and event body (StackTraceResponseBody, StoppedEventBody ...), with
 *     getters, chainable setters, toJson() and fromJson(); members the schema doesn't name are
 *     kept in getExtra() (launch and attach arguments are adapter specific);</li>
 * <li>a class of constants for each string enumeration, and for enumerated members (for
 *     example StoppedEventBody.Reason.BREAKPOINT);</li>
 * <li>IDebugAdapter (the requests an adapter answers) and IDebugClient (the events and
 *     reverse requests a client gets), with default methods;</li>
 * <li>AdapterDispatcher and ClientDispatcher (incoming JSON to those interfaces) and
 *     DebugAdapterProxy and DebugClientProxy (those interfaces to outgoing JSON).</li>
 * </ul>
 * The generated sources are kept in git; TestGenerated checks that they match the schema.
 */
public class DapGenerator {

	static final String PACKAGE = "us.bringardner.parley.dap.protocol";
	static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
			"char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
			"finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
			"long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
			"strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "var", "record", "yield", "true", "false", "null");

	private final Map<String,Object> defs;
	private final String header;
	private final Map<String,String> files = new TreeMap<>();

	/** A request, its arguments and response. */
	static final class Req {
		String command;
		String name;          // Initialize
		String argsType;      // InitializeRequestArguments, or null for none
		String responseType;  // Capabilities, StackTraceResponseBody, or null (no body)
		String description;
		boolean reverse;
	}

	/** An event and its body. */
	static final class Evt {
		String event;
		String bodyType;      // StoppedEventBody, or null
		String description;
	}

	private final List<Req> requests = new ArrayList<>();
	private final List<Evt> events = new ArrayList<>();

	@SuppressWarnings("unchecked")
	DapGenerator(String schemaText, String header) throws IOException {
		Map<String,Object> schema = Json.parseObject(schemaText);
		this.defs = (Map<String,Object>) schema.get("definitions");
		this.header = header;
	}

	public static void main(String[] args) throws IOException {
		if( args.length != 2 ) {
			System.err.println("Usage: DapGenerator SCHEMA.json SOURCE-ROOT");
			System.exit(2);
		}
		Path out = Paths.get(args[1]);
		Map<String,String> files = generate(Files.readString(Paths.get(args[0]), StandardCharsets.UTF_8));
		Path dir = out.resolve(PACKAGE.replace('.', '/'));
		Files.createDirectories(dir);
		// remove classes that are no longer generated
		try(java.util.stream.Stream<Path> old = Files.list(dir)) {
			for(Path p : (Iterable<Path>) old::iterator) {
				if( p.toString().endsWith(".java") && !files.containsKey(p.getFileName().toString())) {
					Files.delete(p);
				}
			}
		}
		for(Map.Entry<String,String> e : files.entrySet()) {
			Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
		}
		System.out.println("Generated "+files.size()+" files in "+dir);
	}

	/** File name -> source for the schema. */
	public static Map<String,String> generate(String schemaText) throws IOException {
		String header = "";
		try(java.io.InputStream in = DapGenerator.class.getResourceAsStream("header.txt")) {
			if( in != null ) {
				header = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		DapGenerator g = new DapGenerator(schemaText, header);
		g.run();
		return g.files;
	}

	// ------------------------------------------------------------------ schema helpers

	@SuppressWarnings("unchecked")
	static Map<String,Object> map(Object o) {
		return (Map<String,Object>) o;
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Object o) {
		return o == null ? new ArrayList<>() : (List<Object>) o;
	}

	static String refName(Object ref) {
		String s = (String) ref;
		return s.substring(s.lastIndexOf('/')+1);
	}

	/** The part of an allOf definition after its base, and the base's name. */
	static String baseOf(Map<String,Object> def) {
		List<Object> all = list(def.get("allOf"));
		return all.isEmpty() ? null : refName(map(all.get(0)).get("$ref"));
	}

	static Map<String,Object> ownPart(Map<String,Object> def) {
		if( def.containsKey("allOf")) {
			List<Object> all = list(def.get("allOf"));
			return all.size() > 1 ? map(all.get(1)) : new LinkedHashMap<>();
		}
		return def;
	}

	static String cap(String s) {
		return Character.toUpperCase(s.charAt(0))+s.substring(1);
	}

	/** A member name as a Java identifier: leading underscores dropped, keywords given a trailing _. */
	static String javaName(String member) {
		String s = member;
		while( s.startsWith("_")) {
			s = s.substring(1);
		}
		StringBuilder b = new StringBuilder();
		boolean up = false;
		for(char c : s.toCharArray()) {
			if( Character.isLetterOrDigit(c)) {
				b.append(up ? Character.toUpperCase(c) : c);
				up = false;
			} else {
				up = b.length() > 0;
			}
		}
		String ret = b.toString();
		if( ret.isEmpty() || Character.isDigit(ret.charAt(0))) {
			ret = "m"+ret;
		}
		return KEYWORDS.contains(ret) ? ret+"_" : ret;
	}

	/** A constant name for an enumerated value: "function breakpoint" -> FUNCTION_BREAKPOINT. */
	static String constName(String value) {
		StringBuilder b = new StringBuilder();
		char prev = 0;
		for(char c : value.toCharArray()) {
			if( Character.isLetterOrDigit(c)) {
				if( Character.isUpperCase(c) && Character.isLowerCase(prev)) {
					b.append('_');
				}
				b.append(Character.toUpperCase(c));
			} else if( b.length() > 0 && b.charAt(b.length()-1) != '_') {
				b.append('_');
			}
			prev = c;
		}
		String ret = b.toString();
		while( ret.endsWith("_")) {
			ret = ret.substring(0, ret.length()-1);
		}
		if( ret.isEmpty() || Character.isDigit(ret.charAt(0))) {
			ret = "V_"+ret;
		}
		return ret;
	}

	static String javadoc(String indent, String text) {
		if( text == null || text.isBlank()) {
			return "";
		}
		String t = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("*/", "*&#47;").replace("@", "&#64;");
		StringBuilder b = new StringBuilder(indent).append("/**\n");
		for(String line : t.split("\n")) {
			b.append(indent).append(" * ").append(line).append('\n');
		}
		return b.append(indent).append(" */\n").toString();
	}

	static String quote(String s) {
		StringBuilder b = new StringBuilder();
		Json.quote(b, s);
		return b.toString();
	}

	// ------------------------------------------------------------------ types

	/** How a member is held in Java and read/written as JSON. */
	static final class JType {
		String java;          // the Java type
		String read;          // expression reading JSON value V (with name W), may throw JsonException
		String write;         // expression writing field F to JSON
		String imports;       // extra import, or null
	}

	JType type(Map<String,Object> p, String ownerClass, String member) {
		JType t = new JType();
		Object type = p.get("type");
		if( p.containsKey("$ref")) {
			String name = refName(p.get("$ref"));
			Map<String,Object> def = map(defs.get(name));
			if( "string".equals(def.get("type"))) {
				// a string enumeration: constants in class 'name'
				return simple("String", "JsonValues.string($V$, $W$)", "$F$");
			}
			t.java = name;
			t.read = "$V$ == null ? null : "+name+".fromJson($V$)";
			t.write = "JsonValues.toJson($F$)";
			return t;
		}
		if( type instanceof List || p.containsKey("oneOf")) {
			// several types: the JSON value as it is (see Json for the Java types)
			return simple("Object", "JsonValues.any($V$, $W$)", "$F$");
		}
		String ty = (String) type;
		if( ty == null ) {
			return simple("Object", "JsonValues.any($V$, $W$)", "$F$");
		}
		switch (ty) {
		case "string": return simple("String", "JsonValues.string($V$, $W$)", "$F$");
		case "integer": return simple("Integer", "JsonValues.integer($V$, $W$)", "$F$");
		case "number": return simple("Double", "JsonValues.number($V$, $W$)", "$F$");
		case "boolean": return simple("Boolean", "JsonValues.bool($V$, $W$)", "$F$");
		case "array": {
			JType item = type(map(p.get("items")), ownerClass, member);
			t.java = "List<"+item.java+">";
			String itemRead = item.read.replace("$V$", "v").replace("$W$", "w");
			t.read = "JsonValues.list($V$, $W$, (v, w) -> "+itemRead+")";
			t.write = item.write.equals("$F$") ? "$F$" : "JsonValues.toJson($F$, x -> "+item.write.replace("$F$", "x")+")";
			return t;
		}
		case "object": {
			Object addl = p.get("additionalProperties");
			if( addl instanceof Map ) {
				JType item = type(map(addl), ownerClass, member);
				t.java = "Map<String,"+item.java+">";
				String itemRead = item.read.replace("$V$", "v").replace("$W$", "w");
				t.read = "JsonValues.map($V$, $W$, (v, w) -> "+itemRead+")";
				t.write = item.write.equals("$F$") ? "$F$" : "JsonValues.toJson($F$, x -> "+item.write.replace("$F$", "x")+")";
				return t;
			}
			if( p.containsKey("properties")) {
				throw new IllegalStateException("Inline object at "+ownerClass+"."+member);
			}
			return simple("Map<String,Object>", "JsonValues.map($V$, $W$, JsonValues::any)", "$F$");
		}
		default:
			return simple("Object", "JsonValues.any($V$, $W$)", "$F$");
		}
	}

	static JType simple(String java, String read, String write) {
		JType t = new JType();
		t.java = java;
		t.read = read;
		t.write = write;
		return t;
	}

	/** A member of a generated class. */
	static final class Member {
		String json;
		String field;
		String prop;          // Capitalized, for getX / setX
		JType type;
		String description;
		List<String> enumValues;
		boolean required;
	}

	List<Member> members(String className, Map<String,Object> part) {
		List<Member> ret = new ArrayList<>();
		Map<String,Object> props = map(part.getOrDefault("properties", new LinkedHashMap<>()));
		Set<String> required = new LinkedHashSet<>();
		for(Object r : list(part.get("required"))) {
			required.add((String) r);
		}
		for(Map.Entry<String,Object> e : props.entrySet()) {
			Map<String,Object> p = map(e.getValue());
			Member m = new Member();
			m.json = e.getKey();
			m.field = javaName(e.getKey());
			String base = m.field.endsWith("_") ? m.field.substring(0, m.field.length()-1) : m.field;
			m.prop = cap(base);
			m.type = type(p, className, e.getKey());
			m.description = (String) p.get("description");
			m.required = required.contains(e.getKey());
			Object en = p.containsKey("enum") ? p.get("enum") : p.get("_enum");
			if( en != null && m.type.java.equals("String")) {
				m.enumValues = new ArrayList<>();
				for(Object v : list(en)) {
					m.enumValues.add((String) v);
				}
			}
			ret.add(m);
		}
		return ret;
	}

	// ------------------------------------------------------------------ generation

	void run() {
		String section = "";
		for(Map.Entry<String,Object> e : defs.entrySet()) {
			String name = e.getKey();
			Map<String,Object> def = map(e.getValue());
			Map<String,Object> own = ownPart(def);
			if( own.get("title") instanceof String ) {
				section = (String) own.get("title");
			}
			String base = baseOf(def);
			if( base == null ) {
				if( "string".equals(def.get("type"))) {
					constants(name, def);
				} else if( "object".equals(def.get("type"))) {
					if( !name.equals("ProtocolMessage")) {
						dataClass(name, null, def, (String) def.get("description"));
					}
				}
				continue;
			}
			switch (base) {
			case "Request":
				request(name, own, section.equals("Reverse Requests"));
				break;
			case "Response":
				response(name, own);
				break;
			case "Event":
				event(name, own);
				break;
			case "ProtocolMessage":
				// Request, Response, Event: the envelopes (DapEndpoint)
				break;
			default:
				// a type that extends another (StackFrameFormat extends ValueFormat)
				dataClass(name, base, own, (String) own.get("description"));
			}
		}
		adapterInterface();
		clientInterface();
		adapterDispatcher();
		clientDispatcher();
		adapterProxy();
		clientProxy();
	}

	private void request(String name, Map<String,Object> own, boolean reverse) {
		Map<String,Object> props = map(own.get("properties"));
		Req r = new Req();
		r.command = (String) list(map(props.get("command")).get("enum")).get(0);
		r.name = name.substring(0, name.length()-"Request".length());
		Object args = props.get("arguments");
		r.argsType = args == null ? null : refName(map(args).get("$ref"));
		r.description = (String) own.get("description");
		r.reverse = reverse;
		Map<String,Object> resp = map(defs.get(r.name+"Response"));
		if( resp != null ) {
			Map<String,Object> rp = map(ownPart(resp).getOrDefault("properties", new LinkedHashMap<>()));
			Object body = rp.get("body");
			if( body != null ) {
				Map<String,Object> b = map(body);
				if( b.containsKey("$ref")) {
					r.responseType = refName(b.get("$ref"));
				} else if( b.containsKey("properties")) {
					r.responseType = r.name+"ResponseBody";
				}
			}
		}
		requests.add(r);
	}

	private void response(String name, Map<String,Object> own) {
		Map<String,Object> props = map(own.getOrDefault("properties", new LinkedHashMap<>()));
		Object body = props.get("body");
		if( body != null && map(body).containsKey("properties")) {
			String cls = name+"Body";
			dataClass(cls, null, map(body), "The body of the response to the "
					+lowerFirst(name.substring(0, name.length()-"Response".length()))+" request.\n"
					+(own.get("description") == null ? "" : own.get("description")));
		}
	}

	private void event(String name, Map<String,Object> own) {
		Map<String,Object> props = map(own.get("properties"));
		Evt e = new Evt();
		e.event = (String) list(map(props.get("event")).get("enum")).get(0);
		e.description = (String) own.get("description");
		Object body = props.get("body");
		if( body != null && map(body).containsKey("properties")) {
			e.bodyType = name+"Body";
			dataClass(e.bodyType, null, map(body), "The body of the " +e.event+" event.\n"
					+(e.description == null ? "" : e.description));
		}
		events.add(e);
	}

	static String lowerFirst(String s) {
		return Character.toLowerCase(s.charAt(0))+s.substring(1);
	}

	private String start(String imports) {
		return header
			+"package "+PACKAGE+";\n\n"
			+imports
			+"// Generated by DapGenerator from schema/debugAdapterProtocol.json: don't edit, regenerate.\n\n";
	}

	private void constants(String name, Map<String,Object> def) {
		StringBuilder b = new StringBuilder(start(""));
		b.append(javadoc("", (String) def.get("description")));
		b.append("public final class ").append(name).append(" {\n\n");
		Object en = def.containsKey("enum") ? def.get("enum") : def.get("_enum");
		List<Object> descs = list(def.get("enumDescriptions"));
		int i = 0;
		for(Object v : list(en)) {
			if( i < descs.size()) {
				b.append(javadoc("\t", (String) descs.get(i)));
			}
			b.append("\tpublic static final String ").append(constName((String) v)).append(" = ").append(quote((String) v)).append(";\n");
			i++;
		}
		b.append("\n\tprivate ").append(name).append("() {\n\t}\n}\n");
		files.put(name+".java", b.toString());
	}

	private void dataClass(String name, String superClass, Map<String,Object> part, String description) {
		List<Member> members = members(name, part);
		boolean usesList = false;
		boolean usesMap = true;
		for(Member m : members) {
			usesList |= m.type.java.contains("List<");
		}
		StringBuilder imp = new StringBuilder();
		if( usesList ) {
			imp.append("import java.util.List;\n");
		}
		if( usesMap ) {
			imp.append("import java.util.LinkedHashMap;\nimport java.util.Map;\n");
		}
		imp.append("import java.util.Set;\n\n");
		imp.append("import us.bringardner.parley.dap.json.Json;\n");
		imp.append("import us.bringardner.parley.dap.json.JsonException;\n");
		imp.append("import us.bringardner.parley.dap.json.JsonValue;\n");
		imp.append("import us.bringardner.parley.dap.json.JsonValues;\n\n");
		StringBuilder b = new StringBuilder(start(imp.toString()));
		b.append(javadoc("", description));
		b.append("public class ").append(name);
		if( superClass != null ) {
			b.append(" extends ").append(superClass);
		} else {
			b.append(" implements JsonValue");
		}
		b.append(" {\n\n");

		// member names
		b.append("\t/** The members this class has (others are kept in getExtra()). */\n");
		b.append("\tpublic static final Set<String> MEMBERS = Set.of(");
		List<String> names = new ArrayList<>();
		if( superClass != null ) {
			for(Member m : members(superClass, ownPart(map(defs.get(superClass))))) {
				names.add(quote(m.json));
			}
		}
		for(Member m : members) {
			names.add(quote(m.json));
		}
		b.append(String.join(", ", names)).append(");\n\n");

		// enumerated members' constants
		Set<String> nested = new LinkedHashSet<>();
		for(Member m : members) {
			if( m.enumValues != null && m.enumValues.size() > 0 ) {
				String cn = m.prop;
				if( !nested.add(cn) || cn.equals(name)) {
					continue;
				}
				b.append("\t/** Values of ").append(m.json).append(". */\n");
				b.append("\tpublic static final class ").append(cn).append(" {\n");
				for(String v : m.enumValues) {
					b.append("\t\tpublic static final String ").append(constName(v)).append(" = ").append(quote(v)).append(";\n");
				}
				b.append("\n\t\tprivate ").append(cn).append("() {\n\t\t}\n\t}\n\n");
			}
		}

		for(Member m : members) {
			b.append("\tprivate ").append(m.type.java).append(' ').append(m.field).append(";\n");
		}
		if( superClass == null ) {
			b.append("\tprivate Map<String,Object> extra;\n");
		}
		b.append('\n');

		for(Member m : members) {
			String desc = m.description == null ? "" : m.description;
			if( m.required ) {
				desc = desc+(desc.isEmpty() ? "" : "\n")+"(Required.)";
			}
			if( m.enumValues != null ) {
				desc = desc+(desc.isEmpty() ? "" : "\n")+"See "+name+"."+m.prop+".";
			}
			b.append(javadoc("\t", desc));
			b.append("\tpublic ").append(m.type.java).append(" get").append(m.prop).append("() {\n");
			b.append("\t\treturn ").append(m.field).append(";\n\t}\n\n");
			b.append("\tpublic ").append(name).append(" set").append(m.prop).append('(').append(m.type.java).append(' ')
				.append(m.field).append(") {\n");
			b.append("\t\tthis.").append(m.field).append(" = ").append(m.field).append(";\n\t\treturn this;\n\t}\n\n");
		}
		if( superClass == null ) {
			b.append("\t/** Members the protocol doesn't define (adapter specific ones), as JSON values; null if none. */\n");
			b.append("\tpublic Map<String,Object> getExtra() {\n\t\treturn extra;\n\t}\n\n");
			b.append("\tpublic ").append(name).append(" setExtra(Map<String,Object> extra) {\n\t\tthis.extra = extra;\n\t\treturn this;\n\t}\n\n");
		}

		// toJson
		b.append("\t@Override\n\tpublic Map<String,Object> toJson() {\n");
		if( superClass != null ) {
			b.append("\t\tMap<String,Object> json = super.toJson();\n");
		} else {
			b.append("\t\tMap<String,Object> json = new LinkedHashMap<>();\n");
		}
		for(Member m : members) {
			b.append("\t\tJsonValues.put(json, ").append(quote(m.json)).append(", ").append(m.type.write.replace("$F$", "this."+m.field)).append(");\n");
		}
		if( superClass == null ) {
			b.append("\t\tif( extra != null ) {\n\t\t\tfor(Map.Entry<String,Object> e : extra.entrySet()) {\n");
			b.append("\t\t\t\tjson.putIfAbsent(e.getKey(), e.getValue());\n\t\t\t}\n\t\t}\n");
		}
		b.append("\t\treturn json;\n\t}\n\n");

		// fromJson
		b.append("\t/** Reads one from JSON (a parsed object). @throws JsonException if a member has the wrong type */\n");
		b.append("\tpublic static ").append(name).append(" fromJson(Object value) throws JsonException {\n");
		b.append("\t\tMap<String,Object> json = JsonValues.object(value, ").append(quote(name)).append(");\n");
		b.append("\t\t").append(name).append(" ret = new ").append(name).append("();\n");
		b.append("\t\tret.read(json);\n");
		b.append("\t\tfor(Map.Entry<String,Object> e : json.entrySet()) {\n");
		b.append("\t\t\tif( !MEMBERS.contains(e.getKey())) {\n");
		b.append("\t\t\t\tif( ret.getExtra() == null ) {\n\t\t\t\t\tret.setExtra(new LinkedHashMap<>());\n\t\t\t\t}\n");
		b.append("\t\t\t\tret.getExtra().put(e.getKey(), e.getValue());\n\t\t\t}\n\t\t}\n");
		b.append("\t\treturn ret;\n\t}\n\n");
		b.append("\tprotected void read(Map<String,Object> json) throws JsonException {\n");
		if( superClass != null ) {
			b.append("\t\tsuper.read(json);\n");
		}
		for(Member m : members) {
			String read = m.type.read.replace("$V$", "json.get("+quote(m.json)+")").replace("$W$", quote(name+"."+m.json));
			b.append("\t\tthis.").append(m.field).append(" = ").append(read).append(";\n");
		}
		b.append("\t}\n\n");

		if( superClass == null ) {
			b.append("\t@Override\n\tpublic boolean equals(Object o) {\n");
			b.append("\t\treturn o != null && o.getClass() == getClass() && toJson().equals(((JsonValue) o).toJson());\n\t}\n\n");
			b.append("\t@Override\n\tpublic int hashCode() {\n\t\treturn toJson().hashCode();\n\t}\n\n");
			b.append("\t/** The JSON text. */\n\t@Override\n\tpublic String toString() {\n\t\treturn Json.write(toJson());\n\t}\n");
		}
		b.append("}\n");
		files.put(name+".java", b.toString());
	}

	// ------------------------------------------------------------------ interfaces

	static String method(String command) {
		return KEYWORDS.contains(command) ? command+"_" : command;
	}

	static String result(String type) {
		return type == null ? "Void" : type;
	}

	private void adapterInterface() {
		StringBuilder b = new StringBuilder(start("import java.util.concurrent.CompletableFuture;\n\n"
				+"import us.bringardner.parley.dap.DapException;\n\n"));
		b.append("/**\n * A debug adapter: the requests a client (the IDE) sends. Implement the ones the adapter supports;\n");
		b.append(" * the others answer \"notSupported\". A request fails with a DapException (thrown, or as the\n");
		b.append(" * future's failure) to send an error response.\n");
		b.append(" * <p>\n * The adapter talks back to the client (events, reverse requests) through an IDebugClient\n");
		b.append(" * (DapConnection.getClient(), or the one given to an adapter factory).\n */\n");
		b.append("public interface IDebugAdapter {\n\n");
		for(Req r : requests) {
			if( r.reverse ) {
				continue;
			}
			b.append(javadoc("\t", r.description));
			b.append("\tdefault CompletableFuture<").append(result(r.responseType)).append("> ").append(method(r.command)).append('(');
			if( r.argsType != null ) {
				b.append(r.argsType).append(" args");
			}
			b.append(") {\n\t\treturn notSupported(").append(quote(r.command)).append(");\n\t}\n\n");
		}
		b.append("\t/**\n\t * A request the protocol doesn't define (an adapter's own).\n");
		b.append("\t * @param arguments its arguments as JSON values, or null\n");
		b.append("\t * @return the response body (JSON values or a JsonValue), or null\n\t */\n");
		b.append("\tdefault CompletableFuture<Object> otherRequest(String command, Object arguments) {\n");
		b.append("\t\treturn notSupported(command);\n\t}\n\n");
		b.append("\t/** A future failed with DapException(\"notSupported\"). */\n");
		b.append("\tstatic <T> CompletableFuture<T> notSupported(String command) {\n");
		b.append("\t\tCompletableFuture<T> f = new CompletableFuture<>();\n");
		b.append("\t\tf.completeExceptionally(new DapException(\"notSupported\"));\n\t\treturn f;\n\t}\n}\n");
		files.put("IDebugAdapter.java", b.toString());
	}

	private void clientInterface() {
		StringBuilder b = new StringBuilder(start("import java.util.concurrent.CompletableFuture;\n\n"));
		b.append("/**\n * A debug client (the IDE): the events an adapter sends, and the reverse requests it may make.\n");
		b.append(" * Events do nothing unless overridden; reverse requests answer \"notSupported\".\n */\n");
		b.append("public interface IDebugClient {\n\n");
		for(Evt e : events) {
			b.append(javadoc("\t", e.description));
			b.append("\tdefault void ").append(method(e.event)).append('(');
			if( e.bodyType != null ) {
				b.append(e.bodyType).append(" body");
			}
			b.append(") {\n\t}\n\n");
		}
		for(Req r : requests) {
			if( !r.reverse ) {
				continue;
			}
			b.append(javadoc("\t", r.description));
			b.append("\tdefault CompletableFuture<").append(result(r.responseType)).append("> ").append(method(r.command)).append('(');
			if( r.argsType != null ) {
				b.append(r.argsType).append(" args");
			}
			b.append(") {\n\t\treturn IDebugAdapter.notSupported(").append(quote(r.command)).append(");\n\t}\n\n");
		}
		b.append("\t/** An event the protocol doesn't define (body as JSON values, or null). */\n");
		b.append("\tdefault void otherEvent(String event, Object body) {\n\t}\n\n");
		b.append("\t/** A reverse request the protocol doesn't define. */\n");
		b.append("\tdefault CompletableFuture<Object> otherRequest(String command, Object arguments) {\n");
		b.append("\t\treturn IDebugAdapter.notSupported(command);\n\t}\n}\n");
		files.put("IDebugClient.java", b.toString());
	}

	private void dispatchRequests(StringBuilder b, String target, boolean reverse) {
		b.append("\t@Override\n\t@SuppressWarnings(\"unchecked\")\n");
		b.append("\tpublic CompletableFuture<Object> request(String command, Object arguments) {\n");
		b.append("\t\ttry {\n\t\t\tswitch (command) {\n");
		for(Req r : requests) {
			if( r.reverse != reverse ) {
				continue;
			}
			b.append("\t\t\tcase ").append(quote(r.command)).append(":\n");
			b.append("\t\t\t\treturn (CompletableFuture<Object>) (CompletableFuture<?>) ").append(target).append('.').append(method(r.command)).append('(');
			if( r.argsType != null ) {
				b.append("arguments == null ? new ").append(r.argsType).append("() : ").append(r.argsType).append(".fromJson(arguments)");
			}
			b.append(");\n");
		}
		b.append("\t\t\tdefault:\n\t\t\t\treturn ").append(target).append(".otherRequest(command, arguments);\n");
		b.append("\t\t\t}\n\t\t} catch (JsonException e) {\n");
		b.append("\t\t\tCompletableFuture<Object> f = new CompletableFuture<>();\n");
		b.append("\t\t\tf.completeExceptionally(new DapException(\"Bad arguments for \"+command+\": \"+e.getMessage()));\n");
		b.append("\t\t\treturn f;\n\t\t}\n\t}\n\n");
	}

	private void adapterDispatcher() {
		StringBuilder b = new StringBuilder(start("import java.util.concurrent.CompletableFuture;\n\n"
				+"import us.bringardner.parley.dap.DapEndpoint;\nimport us.bringardner.parley.dap.DapException;\n"
				+"import us.bringardner.parley.dap.json.JsonException;\n\n"));
		b.append("/** Hands the requests that arrive at an adapter to its IDebugAdapter. */\n");
		b.append("public final class AdapterDispatcher implements DapEndpoint.Handler {\n\n");
		b.append("\tprivate final IDebugAdapter adapter;\n\n");
		b.append("\tpublic AdapterDispatcher(IDebugAdapter adapter) {\n\t\tthis.adapter = adapter;\n\t}\n\n");
		dispatchRequests(b, "adapter", false);
		b.append("\t/** Clients send no events: ignored. */\n\t@Override\n\tpublic void event(String event, Object body) {\n\t}\n}\n");
		files.put("AdapterDispatcher.java", b.toString());
	}

	private void clientDispatcher() {
		StringBuilder b = new StringBuilder(start("import java.util.concurrent.CompletableFuture;\n\n"
				+"import us.bringardner.parley.dap.DapEndpoint;\nimport us.bringardner.parley.dap.DapException;\n"
				+"import us.bringardner.parley.dap.json.JsonException;\n\n"));
		b.append("/** Hands the events and reverse requests that arrive at a client to its IDebugClient. */\n");
		b.append("public final class ClientDispatcher implements DapEndpoint.Handler {\n\n");
		b.append("\tprivate final IDebugClient client;\n\tprivate final java.util.function.Consumer<Throwable> errors;\n\n");
		b.append("\t/** @param errors told about events whose bodies can't be read */\n");
		b.append("\tpublic ClientDispatcher(IDebugClient client, java.util.function.Consumer<Throwable> errors) {\n");
		b.append("\t\tthis.client = client;\n\t\tthis.errors = errors;\n\t}\n\n");
		dispatchRequests(b, "client", true);
		b.append("\t@Override\n\tpublic void event(String event, Object body) {\n\t\ttry {\n\t\t\tswitch (event) {\n");
		for(Evt e : events) {
			b.append("\t\t\tcase ").append(quote(e.event)).append(":\n\t\t\t\tclient.").append(method(e.event)).append('(');
			if( e.bodyType != null ) {
				b.append("body == null ? new ").append(e.bodyType).append("() : ").append(e.bodyType).append(".fromJson(body)");
			}
			b.append(");\n\t\t\t\tbreak;\n");
		}
		b.append("\t\t\tdefault:\n\t\t\t\tclient.otherEvent(event, body);\n\t\t\t}\n");
		b.append("\t\t} catch (JsonException e) {\n\t\t\terrors.accept(e);\n\t\t}\n\t}\n}\n");
		files.put("ClientDispatcher.java", b.toString());
	}

	private void proxyRequest(StringBuilder b, Req r) {
		b.append("\t@Override\n\tpublic CompletableFuture<").append(result(r.responseType)).append("> ").append(method(r.command)).append('(');
		if( r.argsType != null ) {
			b.append(r.argsType).append(" args");
		}
		b.append(") {\n\t\treturn endpoint.request(").append(quote(r.command)).append(", ")
			.append(r.argsType == null ? "null" : "JsonValues.toJson(args)").append(")");
		if( r.responseType == null ) {
			b.append(".thenApply(body -> null);\n\t}\n\n");
		} else {
			b.append(".thenApply(body -> read(body, (v, w) -> ").append(r.responseType).append(".fromJson(v)));\n\t}\n\n");
		}
	}

	static final String READ =
			"\tprivate static <T> T read(Object body, JsonValues.Reader<T> reader) {\n"
			+"\t\ttry {\n\t\t\treturn body == null ? null : reader.read(body, \"body\");\n"
			+"\t\t} catch (JsonException e) {\n\t\t\tthrow new CompletionException(e);\n\t\t}\n\t}\n\n";

	private void adapterProxy() {
		StringBuilder b = new StringBuilder(start("import java.util.concurrent.CompletableFuture;\nimport java.util.concurrent.CompletionException;\n\n"
				+"import us.bringardner.parley.dap.DapEndpoint;\n"
				+"import us.bringardner.parley.dap.json.JsonException;\nimport us.bringardner.parley.dap.json.JsonValues;\n\n"));
		b.append("/**\n * A client's view of the adapter at the other end: each call sends the request and the future\n");
		b.append(" * completes with the response's body (or fails with DapException for an error response).\n */\n");
		b.append("public final class DebugAdapterProxy implements IDebugAdapter {\n\n");
		b.append("\tprivate final DapEndpoint endpoint;\n\n");
		b.append("\tpublic DebugAdapterProxy(DapEndpoint endpoint) {\n\t\tthis.endpoint = endpoint;\n\t}\n\n");
		b.append(READ);
		for(Req r : requests) {
			if( !r.reverse ) {
				proxyRequest(b, r);
			}
		}
		b.append("\t@Override\n\tpublic CompletableFuture<Object> otherRequest(String command, Object arguments) {\n");
		b.append("\t\treturn endpoint.request(command, arguments);\n\t}\n}\n");
		files.put("DebugAdapterProxy.java", b.toString());
	}

	private void clientProxy() {
		StringBuilder b = new StringBuilder(start("import java.io.IOException;\nimport java.util.concurrent.CompletableFuture;\nimport java.util.concurrent.CompletionException;\nimport java.util.function.Consumer;\n\n"
				+"import us.bringardner.parley.dap.DapEndpoint;\n"
				+"import us.bringardner.parley.dap.json.JsonException;\nimport us.bringardner.parley.dap.json.JsonValues;\n\n"));
		b.append("/**\n * An adapter's view of the client at the other end: events are sent as they're called (an event\n");
		b.append(" * that can't be sent, because the client has gone, is reported to the error listener and\n");
		b.append(" * otherwise dropped); reverse requests return the response's body.\n */\n");
		b.append("public final class DebugClientProxy implements IDebugClient {\n\n");
		b.append("\tprivate final DapEndpoint endpoint;\n\tprivate final Consumer<Throwable> errors;\n\n");
		b.append("\tpublic DebugClientProxy(DapEndpoint endpoint, Consumer<Throwable> errors) {\n");
		b.append("\t\tthis.endpoint = endpoint;\n\t\tthis.errors = errors;\n\t}\n\n");
		b.append(READ);
		b.append("\tprivate void send(String event, Object body) {\n\t\ttry {\n\t\t\tendpoint.event(event, body);\n");
		b.append("\t\t} catch (IOException e) {\n\t\t\terrors.accept(e);\n\t\t}\n\t}\n\n");
		for(Evt e : events) {
			b.append("\t@Override\n\tpublic void ").append(method(e.event)).append('(');
			if( e.bodyType != null ) {
				b.append(e.bodyType).append(" body");
			}
			b.append(") {\n\t\tsend(").append(quote(e.event)).append(", ").append(e.bodyType == null ? "null" : "JsonValues.toJson(body)").append(");\n\t}\n\n");
		}
		for(Req r : requests) {
			if( r.reverse ) {
				proxyRequest(b, r);
			}
		}
		b.append("\t@Override\n\tpublic void otherEvent(String event, Object body) {\n\t\tsend(event, body);\n\t}\n\n");
		b.append("\t@Override\n\tpublic CompletableFuture<Object> otherRequest(String command, Object arguments) {\n");
		b.append("\t\treturn endpoint.request(command, arguments);\n\t}\n}\n");
		files.put("DebugClientProxy.java", b.toString());
	}

	static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
