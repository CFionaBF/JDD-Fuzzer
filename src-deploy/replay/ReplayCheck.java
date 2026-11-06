import com.sun.jdi.ArrayType;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays one exploit object outside the fuzzer and checks, without changing how the chain runs
 * (no agent, no SecurityManager, no instrumentation: the replay VM runs under the JDK debugger
 * interface with breakpoints at the sink calls):
 *   effect: FILE   - FileOutputStream.write is called on a path containing "jdd_evil_file";
 *           INVOKE - a probe method of OracleClassA (the fuzzer's own oracle class) is called;
 *   chain:  the call stack at that moment passes through the IOCD's gadget methods, entry first, in
 *           order. A gadget method matches a frame of the same method name whose declaring class or
 *           receiver class is, or is a subtype of, the gadget's class.
 *
 * usage: java -cp <this dir>:<tools.jar> ReplayCheck <java home> <debuggee classpath> <payload> <jdk|hessian> <FILE|INVOKE> <chain file>
 *   debuggee classpath: ReplayTarget + the app's jars (+ the oracle classes for INVOKE)
 *   chain file: one gadget method per line, "<class> <method>", entry first
 *   triggered: the chain(s) the payload really ran, read off the sink stacks with the rules of the
 *           fuzzer's datastructrue.inst.TriggeredChain, as their keys
 * stdout: RESULT effect=<ok|fail> chain=<matched>/<total> miss=<first unmatched gadget method|-> triggered=<key,..|->
 * stderr: the debuggee's output and the stack the chain was matched against
 */
public class ReplayCheck {

    static final String MARKER = "jdd_evil_file";
    static final Set<String> PROBE_METHODS = new HashSet<>(Arrays.asList(
            "anOracleMethod", "getOracleField", "getAnOracleMethod", "setOracleField", "getOracleProbe"));
    static final String PROBE_CLASS = "cn.edu.fudan.datastructure.OracleClassA";
    // reachability-marker families: the sink was entered carrying this inert token in the watched argument.
    static final String REACH_MARKER = "JDD-REACH-";
    // JNDI name lookup (Spring): hook JndiTemplate.lookup(String,Class), the marker is argument 0 (the name)
    static final String JNDI_LOOKUP_CLASS = "org.springframework.jndi.JndiTemplate";
    static final String JNDI_LOOKUP_METHOD = "lookup";
    static final String JNDI_LOOKUP_SIG = "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;";
    // JNDI object resolution: NamingManager.getObjectInstance(Object, Name, Context, Hashtable) is static;
    // argument 0 is a javax.naming.Reference whose class name carries the marker
    static final String NAMING_MANAGER_CLASS = "javax.naming.spi.NamingManager";
    static final String NAMING_MANAGER_METHOD = "getObjectInstance";
    static final String NAMING_MANAGER_SIG = "(Ljava/lang/Object;Ljavax/naming/Name;Ljavax/naming/Context;Ljava/util/Hashtable;)Ljava/lang/Object;";
    // host-name resolution: InetAddress.getByName(String) is static; argument 0 is the host, an IPv6 literal
    // from the documentation range 2001:db8::/32 (the fuzzer's FuzzUtils.HOST_MARKER_PREFIX)
    static final String INET_CLASS = "java.net.InetAddress";
    static final String INET_METHOD = "getByName";
    static final String INET_SIG = "(Ljava/lang/String;)Ljava/net/InetAddress;";
    static final String HOST_MARKER_PREFIX = "2001:db8::";
    // clojure.main/eval-opt: static invokeStatic(Object); argument 0 is the source text (a string literal marker)
    static final String CLOJURE_EVAL_CLASS = "clojure.main$eval_opt";
    // EL (Myfaces): the payload's literal-text ValueExpression; the receiver's `text` field is the marker
    static final String EL_LITERAL_CLASS = "org.apache.myfaces.view.facelets.el.ELText$LiteralValueExpression";
    // BeanShell: This.invokeMethod(String, Object[]); the receiver's namespace is named by the marker
    static final String BSH_THIS_CLASS = "bsh.This";
    // JRMP: the sink call sites of the IOCD chains are in LiveRef. LiveRef.getChannel (JRMPClient, called by
    // UnicastRef.newCall) is entered on a LiveRef whose endpoint host is a loopback marker 127.255.x.y;
    // LiveRef.exportObject(Target) (JRMPListener, called by UnicastServerRef.exportObject) on one whose endpoint port is a
    // marker port in 20000-39999 (the fuzzer's FuzzUtils.getReachJrmpHostMarker / getReachJrmpPortMarker). The
    // receiver is watched, not an argument.
    static final String LIVE_REF_CLASS = "sun.rmi.transport.LiveRef";
    static final String LIVE_REF_GET_CHANNEL = "getChannel", LIVE_REF_GET_CHANNEL_SIG = "()Lsun/rmi/transport/Channel;";
    static final String LIVE_REF_EXPORT = "exportObject", LIVE_REF_EXPORT_SIG = "(Lsun/rmi/transport/Target;)V";
    static final String JRMP_HOST_MARKER_PREFIX = "127.255.";
    static final int JRMP_PORT_MARKER_MIN = 20000, JRMP_PORT_MARKER_MAX = 39999;
    // class -> {method, signature} of the marker sinks (fuzzer: AgentGlobalDataContainer.registerMarkerArgSinks);
    // LiveRef has a second one, LIVE_REF_EXPORT (see setBreakpoints)
    static final Map<String, String[]> MARKER_SINKS = new java.util.HashMap<>();
    static {
        MARKER_SINKS.put(LIVE_REF_CLASS, new String[]{LIVE_REF_GET_CHANNEL, LIVE_REF_GET_CHANNEL_SIG});
        MARKER_SINKS.put(JNDI_LOOKUP_CLASS, new String[]{JNDI_LOOKUP_METHOD, JNDI_LOOKUP_SIG});
        MARKER_SINKS.put(NAMING_MANAGER_CLASS, new String[]{NAMING_MANAGER_METHOD, NAMING_MANAGER_SIG});
        MARKER_SINKS.put(INET_CLASS, new String[]{INET_METHOD, INET_SIG});
        MARKER_SINKS.put(CLOJURE_EVAL_CLASS, new String[]{"invokeStatic", "(Ljava/lang/Object;)Ljava/lang/Object;"});
        MARKER_SINKS.put(EL_LITERAL_CLASS, new String[]{"getValue", "(Ljavax/el/ELContext;)Ljava/lang/Object;"});
        MARKER_SINKS.put(BSH_THIS_CLASS, new String[]{"invokeMethod", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"});
    }

    // one captured frame: method name + every type the frame's class/receiver is or extends
    static class Frame {
        String text;
        String clz;
        String method;
        Set<String> types = new HashSet<>();
    }

    static String sink;
    // INVOKE and CUSTOM (groovy closure) sinks are verified by the probe method of OracleClassA being called
    static boolean probeSink;
    static final List<List<Frame>> hits = new ArrayList<>();
    static final Set<String> writtenPaths = new LinkedHashSet<>();

    public static void main(String[] a) throws Exception {
        String javaHome = a[0], cp = a[1], exploit = a[2], protocol = a[3];
        sink = a[4];
        List<String[]> chain = new ArrayList<>();
        for (String line : Files.readAllLines(new File(a[5]).toPath(), StandardCharsets.UTF_8)) {
            String[] p = line.trim().split(" ");
            if (p.length == 2) {
                chain.add(p);
            }
        }
        probeSink = sink.equals("INVOKE") || sink.equals("CUSTOM");   // the effect is the fuzzer's probe being called
        if (!sink.equals("FILE") && !probeSink && !sink.equals("JNDI") && !sink.equals("CLOJURE")) {
            System.out.println("RESULT effect=unsupported chain=0/" + chain.size() + " miss=-");
            return;
        }
        if (sink.equals("FILE")) {
            prepareSandboxes(Files.readAllBytes(new File(exploit).toPath()));
        }

        LaunchingConnector lc = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> args = lc.defaultArguments();
        args.get("home").setValue(javaHome);
        args.get("options").setValue("-cp " + cp);
        args.get("main").setValue("ReplayTarget " + exploit + " " + protocol);
        VirtualMachine vm = lc.launch(args);
        drain(vm.process().getInputStream());
        drain(vm.process().getErrorStream());

        EventRequestManager erm = vm.eventRequestManager();
        // classes carrying the sink method(s); a JNDI IOCD is one of several families (name lookup,
        // NamingManager.getObjectInstance, host resolution), told apart by which class its chain reaches, so all are
        // watched. CUSTOM families are verified either by the probe (groovy, fastjson2) or by a marker (EL, BeanShell)
        List<String> watchedClasses = sink.equals("FILE") ? Arrays.asList("java.io.FileOutputStream")
                : sink.equals("JNDI") ? Arrays.asList(JNDI_LOOKUP_CLASS, NAMING_MANAGER_CLASS, INET_CLASS, LIVE_REF_CLASS)
                : sink.equals("CLOJURE") ? Arrays.asList(CLOJURE_EVAL_CLASS)
                : sink.equals("CUSTOM") ? Arrays.asList(PROBE_CLASS, EL_LITERAL_CLASS, BSH_THIS_CLASS)
                : Arrays.asList(PROBE_CLASS);
        for (String watched : watchedClasses) {
            // one request per class: several filters on one request would be ANDed
            ClassPrepareRequest cpr = erm.createClassPrepareRequest();
            cpr.addClassFilter(watched);
            cpr.enable();
        }
        try {
            while (true) {
                EventSet set = vm.eventQueue().remove();
                boolean dead = false;
                for (Event e : set) {
                    if (e instanceof VMStartEvent) {
                        for (String watched : watchedClasses) {
                            for (ReferenceType rt : vm.classesByName(watched)) {
                                setBreakpoints(erm, rt);
                            }
                        }
                    } else if (e instanceof ClassPrepareEvent) {
                        setBreakpoints(erm, ((ClassPrepareEvent) e).referenceType());
                    } else if (e instanceof BreakpointEvent) {
                        onBreakpoint((BreakpointEvent) e);
                    } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                        dead = true;
                    }
                }
                if (dead) {
                    break;
                }
                set.resume();
            }
        } catch (VMDisconnectedException ignored) {
            // the replay VM has exited
        }
        vm.process().waitFor();

        // clean up the files the payload wrote
        for (String p : writtenPaths) {
            File f = new File(p);
            System.err.println("[written] " + f.getAbsolutePath() + " (" + f.length() + " bytes)");
            if (f.isFile()) {
                f.delete();
            }
        }

        // the best-matching stack among the sink hits
        int best = 0;
        List<Frame> bestStack = hits.isEmpty() ? new ArrayList<Frame>() : hits.get(0);
        for (List<Frame> st : hits) {
            int m = matchedPrefix(chain, st);
            if (m > best) {
                best = m;
                bestStack = st;
            }
        }
        for (Frame f : bestStack) {
            System.err.println("    at " + f.text);
        }
        Set<String> triggered = new LinkedHashSet<>();
        for (List<Frame> st : hits) {
            List<String> tc = triggeredChain(st);
            if (triggered.add(chainKey(tc))) {
                System.err.println("[triggered " + chainKey(tc) + "] " + String.join(" -> ", tc));
            }
        }
        String miss = best < chain.size() ? chain.get(best)[0] + "." + chain.get(best)[1] : "-";
        System.out.println("RESULT effect=" + (hits.isEmpty() ? "fail" : "ok") + " chain=" + best + "/" + chain.size() + " miss=" + miss
                + " triggered=" + (triggered.isEmpty() ? "-" : String.join(",", triggered)));
    }

    /**
     * The fuzzer's FILE payloads write into /tmp/jdd_evil_file or a per-work-dir /tmp/jdd_evil_file-<8 hex
     * digits> (fuzz.sh); the id has a fixed length because serialized strings are followed by other bytes.
     * Create the ones the payload names, empty, as on a target system where the payload runs first.
     */
    static void prepareSandboxes(byte[] payload) {
        String text = new String(payload, StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/tmp/" + MARKER + "(-[0-9a-f]{8})?").matcher(text);
        Set<String> dirs = new LinkedHashSet<>();
        dirs.add("/tmp/" + MARKER);
        while (m.find()) {
            dirs.add(m.group());
        }
        for (String d : dirs) {
            File dir = new File(d);
            File[] children = dir.listFiles();
            if (children != null) {
                for (File c : children) {
                    c.delete();
                }
            }
            dir.mkdirs();
        }
    }

    static void setBreakpoints(EventRequestManager erm, ReferenceType rt) {
        for (Method m : rt.methods()) {
            String[] markerSink = MARKER_SINKS.get(rt.name());
            boolean wanted = sink.equals("FILE") ? m.name().equals("write")
                    : markerSink != null ? (markerSink[0].equals(m.name()) && markerSink[1].equals(m.signature())
                            || rt.name().equals(LIVE_REF_CLASS) && m.name().equals(LIVE_REF_EXPORT) && m.signature().equals(LIVE_REF_EXPORT_SIG))
                    : PROBE_METHODS.contains(m.name());
            if (wanted && !m.isAbstract() && !m.isNative() && m.location() != null) {
                BreakpointRequest br = erm.createBreakpointRequest(m.location());
                br.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                br.enable();
            }
        }
    }

    static void onBreakpoint(BreakpointEvent be) throws Exception {
        ThreadReference t = be.thread();
        if (sink.equals("FILE")) {
            ObjectReference self = t.frame(0).thisObject();
            Field pf = self.referenceType().fieldByName("path");
            Value v = pf == null ? null : self.getValue(pf);
            if (!(v instanceof StringReference) || !((StringReference) v).value().contains(MARKER)) {
                return;
            }
            writtenPaths.add(((StringReference) v).value());
        } else if (MARKER_SINKS.containsKey(t.frame(0).location().declaringType().name())) {
            // record only when the marked argument / receiver carries our token
            String clz = t.frame(0).location().declaringType().name();
            List<Value> args = t.frame(0).getArgumentValues();
            Value v = args.isEmpty() ? null : args.get(0);
            String seen = null;
            boolean jrmpMarked = false;
            if (clz.equals(LIVE_REF_CLASS)) {
                // JRMP: LiveRef.ep (TCPEndpoint) -> host (getChannel, the client call) or port (exportObject)
                ObjectReference ep = objectField(t.frame(0).thisObject(), "ep");
                if (t.frame(0).location().method().name().equals(LIVE_REF_GET_CHANNEL)) {
                    String host = stringField(ep, "host");
                    jrmpMarked = host != null && host.startsWith(JRMP_HOST_MARKER_PREFIX);
                } else {
                    Field pf = ep == null ? null : ep.referenceType().fieldByName("port");
                    Value pv = pf == null ? null : ep.getValue(pf);
                    jrmpMarked = pv instanceof IntegerValue && ((IntegerValue) pv).value() >= JRMP_PORT_MARKER_MIN
                            && ((IntegerValue) pv).value() <= JRMP_PORT_MARKER_MAX;
                }
            } else if (clz.equals(EL_LITERAL_CLASS)) {
                seen = stringField(t.frame(0).thisObject(), "text");
            } else if (clz.equals(BSH_THIS_CLASS)) {
                ObjectReference self = t.frame(0).thisObject();
                Field nf = self == null ? null : self.referenceType().fieldByName("namespace");
                Value ns = nf == null ? null : self.getValue(nf);
                seen = ns instanceof ObjectReference ? stringField((ObjectReference) ns, "nsName") : null;
            } else if (v instanceof StringReference) {
                seen = ((StringReference) v).value();
            } else if (v instanceof ObjectReference) {
                // a javax.naming.Reference: its class name field carries the marker
                ObjectReference ref = (ObjectReference) v;
                Field cf = ref.referenceType().fieldByName("className");
                Value cv = cf == null ? null : ref.getValue(cf);
                if (cv instanceof StringReference) {
                    seen = ((StringReference) cv).value();
                }
            }
            if (!jrmpMarked && (seen == null || !(seen.contains(REACH_MARKER) || seen.contains(HOST_MARKER_PREFIX)))) {
                return;
            }
        }
        List<Frame> stack = new ArrayList<>();
        for (StackFrame sf : t.frames()) {
            Location loc = sf.location();
            Frame f = new Frame();
            f.method = loc.method().name();
            f.clz = loc.declaringType().name();
            f.text = loc.declaringType().name() + "." + f.method + "(" + sourceName(loc) + ":" + loc.lineNumber() + ")";
            addTypes(loc.declaringType(), f.types);
            if (!loc.method().isStatic() && !loc.method().isNative()) {
                try {
                    ObjectReference self = sf.thisObject();
                    if (self != null) {
                        addTypes(self.referenceType(), f.types);
                    }
                } catch (Exception ignored) {
                    // no receiver available for this frame
                }
            }
            stack.add(f);
        }
        if (t.frame(0).location().declaringType().name().equals(LIVE_REF_CLASS)) {
            // JRMP: only a hit of the thread reading the stream; the DGC client also renews the refs it registered from
            // its own thread, which is a consequence of the read, not a chain rooted in it (as in the fuzzer's oracle)
            boolean reading = false;
            for (Frame f : stack) {
                reading |= f.clz.startsWith("java.io.ObjectInputStream") || f.clz.startsWith("com.caucho.hessian.io.");
            }
            if (!reading) {
                return;
            }
        }
        hits.add(stack);
    }

    static ObjectReference objectField(ObjectReference obj, String name) {
        if (obj == null) {
            return null;
        }
        Field f = obj.referenceType().fieldByName(name);
        Value v = f == null ? null : obj.getValue(f);
        return v instanceof ObjectReference ? (ObjectReference) v : null;
    }

    static String stringField(ObjectReference obj, String name) {
        if (obj == null) {
            return null;
        }
        Field f = obj.referenceType().fieldByName(name);
        Value v = f == null ? null : obj.getValue(f);
        return v instanceof StringReference ? ((StringReference) v).value() : null;
    }

    static String sourceName(Location loc) {
        try {
            return loc.sourceName();
        } catch (Exception e) {
            return "?";
        }
    }

    static void addTypes(ReferenceType rt, Set<String> out) {
        if (rt == null || !out.add(rt.name())) {
            return;
        }
        if (rt instanceof ClassType) {
            addTypes(((ClassType) rt).superclass(), out);
            for (InterfaceType it : ((ClassType) rt).interfaces()) {
                addTypes(it, out);
            }
        } else if (rt instanceof InterfaceType) {
            for (InterfaceType it : ((InterfaceType) rt).superinterfaces()) {
                addTypes(it, out);
            }
        } else if (rt instanceof ArrayType) {
            out.add("java.lang.Object");
        }
    }

    /**
     * The chain the payload ran, from the source the deserializer called last up to the sink call:
     * same rules as datastructrue.inst.TriggeredChain in the fuzzer. The stacks here are taken at the
     * entry of FileOutputStream.write / of the probe method.
     */
    static List<String> triggeredChain(List<Frame> stack) {
        int top = 0;
        while (top < stack.size()) {
            String c = stack.get(top).clz;
            boolean above = c.startsWith("cn.edu.fudan.datastructure.")
                    || (probeSink && (c.startsWith("sun.reflect.") || c.startsWith("jdk.internal.reflect.")))
                    || (sink.equals("FILE") && (c.equals("java.io.File") || c.equals("java.io.FileOutputStream")));
            if (!above) {
                break;
            }
            top++;
        }
        List<String> out = new ArrayList<>();
        if (sink.equals("FILE")) {
            out.add("java.io.FileOutputStream.write");
        }
        List<String> pending = new ArrayList<>();
        for (int i = top; i < stack.size(); i++) {
            String c = stack.get(i).clz;
            if (c.startsWith("java.io.ObjectInputStream") || c.startsWith("java.io.ObjectStreamClass")
                    || c.contains("hessian.io.") || c.contains("burlap.io.") || c.startsWith("cn.edu.fudan.")) {
                break;
            }
            if (c.startsWith("sun.reflect.") || c.startsWith("jdk.internal.reflect.")) {
                continue;
            }
            String f = c.replaceAll("\\$Proxy\\d+", "\\$Proxy").replaceAll("\\$\\$Lambda\\$.*", "\\$\\$Lambda") + "." + stack.get(i).method;
            if (c.startsWith("java.lang.reflect.") && !(i == top && probeSink)) {
                pending.add(f);
                continue;
            }
            out.addAll(pending);
            pending.clear();
            out.add(f);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    static String chainKey(List<String> chain) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(String.join("\n", chain).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(chain.hashCode());
        }
    }

    /** number of leading gadget methods found in the stack in call order (outermost frame first) */
    // soot models a lambda as a class "<owner>$lambda_<name>_<n>__<id>"; IOCD chains list it before the lambda
    // body <owner>.lambda$<name>$<n>. It does not exist at run time, so it is never on a stack (same rule as
    // CommonUtils.isSootLambdaClass in the fuzzer).
    static boolean isSootLambdaClass(String clz) {
        return clz.matches(".*\\$lambda_.*__\\d+");
    }

    static int matchedPrefix(List<String[]> chain, List<Frame> stack) {
        int ci = 0;
        for (int fi = stack.size() - 1; fi >= 0 && ci < chain.size(); fi--) {
            while (ci < chain.size() && isSootLambdaClass(chain.get(ci)[0])) {
                ci++;
            }
            Frame f = stack.get(fi);
            if (ci < chain.size() && f.method.equals(chain.get(ci)[1]) && f.types.contains(chain.get(ci)[0])) {
                ci++;
            }
        }
        while (ci < chain.size() && isSootLambdaClass(chain.get(ci)[0])) {
            ci++;
        }
        return ci;
    }

    static void drain(final InputStream in) {
        Thread th = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    System.err.write(buf, 0, n);
                }
            } catch (Exception ignored) {
                // stream closed
            }
        });
        th.setDaemon(true);
        th.start();
    }
}
