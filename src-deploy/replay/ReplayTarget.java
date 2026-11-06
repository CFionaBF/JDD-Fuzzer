import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;

/**
 * Debuggee of ReplayCheck: deserializes one saved payload, nothing else.
 * usage: ReplayTarget <payload file> <jdk|hessian>
 * The fuzzer saves the exact bytes it validated, so hessian payloads are Hessian 2 streams, read as
 * the fuzzer reads them (Hessian2Input, non-Serializable classes allowed); the hessian classes come
 * from the app's jars, hence the reflection.
 */
public class ReplayTarget {
    public static void main(String[] a) throws Exception {
        byte[] bytes = Files.readAllBytes(new File(a[0]).toPath());
        setUpJsfRequestContext();
        setUpRhinoContext();
        try {
            // an application using Clojure has its runtime initialized before it reads any stream; initializing a
            // clojure class first (as deserialization may) breaks clojure.lang.RT's own initialization
            Class.forName("clojure.lang.RT");
            System.out.println("[env] clojure runtime initialized");
        } catch (ClassNotFoundException ignored) {
            // not a Clojure application
        }
        try {
            if (a.length > 1 && a[1].equals("hessian")) {
                Class<?> inClz = Class.forName("com.caucho.hessian.io.Hessian2Input");
                Class<?> sfClz = Class.forName("com.caucho.hessian.io.SerializerFactory");
                Object sf = sfClz.newInstance();
                sfClz.getMethod("setAllowNonSerializable", boolean.class).invoke(sf, true);
                Object in = inClz.getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(bytes));
                inClz.getMethod("setSerializerFactory", sfClz).invoke(in, sf);
                inClz.getMethod("readObject").invoke(in);
            } else {
                try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                    in.readObject();
                }
            }
        } catch (Throwable t) {
            // the chain may throw after reaching the sink
            System.out.println("[deserialization threw] " + t);
        }
        exitWhenQuiet();
    }

    /**
     * A chain may start threads that outlive the read: an RMI export (JRMPListener) leaves non-daemon threads that
     * would keep this VM, and so the replay, alive for ever. The sink has been reached (or not) by now; give short-lived
     * threads three seconds to finish, then exit.
     */
    static void exitWhenQuiet() {
        long end = System.currentTimeMillis() + 3000;
        try {
            while (System.currentTimeMillis() < end) {
                boolean busy = false;
                for (Thread th : Thread.getAllStackTraces().keySet()) {
                    if (th != Thread.currentThread() && th.isAlive() && !th.isDaemon()) {
                        busy = true;
                        break;
                    }
                }
                if (!busy) {
                    return;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException ignored) {
            // exit anyway
        }
        System.exit(0);
    }

    /**
     * An application that embeds Rhino evaluates scripts inside Context.enter()/exit(), so the thread that reads a
     * stream has a current Context. Rhino classes read it: ScriptableObject.getImpl, for a property whose getter is
     * a Function, calls Context.getContext(), which throws "No Context associated with current Thread" without one.
     * Reproduce that environment when the app ships Rhino; other apps are left untouched. The [env] line records
     * that the replay established it, so a chain that needs it is not counted as one that fires in a bare JVM.
     */
    static void setUpRhinoContext() {
        Class<?> ctx;
        try {
            ctx = Class.forName("org.mozilla.javascript.Context");
        } catch (Throwable t) {
            return; // not a Rhino application
        }
        try {
            if (ctx.getMethod("getCurrentContext").invoke(null) != null) {
                return;
            }
            ctx.getMethod("enter").invoke(null);
            System.out.println("[env] Rhino Context entered: "
                    + (ctx.getMethod("getCurrentContext").invoke(null) != null));
        } catch (Throwable t) {
            System.out.println("[env] Rhino Context not set up: " + t);
        }
    }

    /**
     * Apps built on MyFaces deserialize inside a JSF request, whose thread has a current FacesContext; MyFaces
     * classes read it (e.g. ValueExpressionMethodExpression gets its ELContext from it). Reproduce that
     * environment, the same objects the fuzzer's seed generator creates (SeedGenerator.
     * generateExpressionInjectionObject), when the app ships MyFaces; other apps are left untouched.
     */
    static void setUpJsfRequestContext() {
        Class<?> fcImpl;
        try {
            fcImpl = Class.forName("org.apache.myfaces.context.servlet.FacesContextImpl");
        } catch (Throwable t) {
            return;
        }
        try {
            Object fc = fcImpl.getConstructor(Class.forName("javax.servlet.ServletContext"),
                    Class.forName("javax.servlet.ServletRequest"), Class.forName("javax.servlet.ServletResponse"))
                    .newInstance(null, null, null);
            Object resolver = Class.forName("org.apache.myfaces.el.CompositeELResolver").newInstance();
            Object elContext = Class.forName("org.apache.myfaces.el.unified.FacesELContext")
                    .getConstructor(Class.forName("javax.el.ELResolver"), Class.forName("javax.faces.context.FacesContext"))
                    .newInstance(resolver, fc);
            java.lang.reflect.Field f = null;
            for (Class<?> c = fcImpl; f == null && c != null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField("_elContext");
                } catch (NoSuchFieldException ignored) {
                    // declared further up
                }
            }
            f.setAccessible(true);
            f.set(fc, elContext);
            Class<?> fcClz = Class.forName("javax.faces.context.FacesContext");
            System.out.println("[env] JSF request context: current FacesContext "
                    + (fcClz.getMethod("getCurrentInstance").invoke(null) != null));
        } catch (Throwable t) {
            System.out.println("[env] JSF request context not set up: " + t);
        }
    }
}
