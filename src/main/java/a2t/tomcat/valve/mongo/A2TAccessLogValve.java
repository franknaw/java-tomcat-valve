package a2t.tomcat.valve.mongo;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpSession;

import org.apache.catalina.AccessLog;
import org.apache.catalina.Globals;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.RequestUtil;
import org.apache.catalina.valves.Constants;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.RequestInfo;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.WriteConcern;

/**
 *  The A2T access log valve is a component that extends the Tomcat Valve API and is inserted into the
 *  request processing pipeline for the A2T Catalina container.
 *  The component writes HTTP access logs to the A2T MongoDB instance.
 *  This project is revamped from https://github.com/chanjarster/tomcat-mongo-access-log
 *  TODO entire rewrite.
 */
public class A2TAccessLogValve extends ValveBase implements AccessLog {

    private static final Log log = LogFactory.getLog(A2TAccessLogValve.class);
    private static final String Package = "a2t.tomcat.valve.mongo";

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(Package);

    //------------------------------------------------------ Constructor
    public A2TAccessLogValve() {
        super(true);
    }

    // ----------------------------------------------------- Instance Variables


    /**
     * The descriptive information about this implementation.
     */
    protected static final String info = "a2t.tomcat.valve.mongo.A2TAccessLogValve/0.1";


    /**
     * The pattern used to format our access log lines.
     */
    protected String pattern = "%a %B %m %s %S %t %U %T %P %{Referer}i %{User-Agent}i %{user}c";

    /**
     * The system time when we last updated the Date that this valve
     * uses for log lines.
     */
    private static final ThreadLocal<Date> localDate = new ThreadLocal<Date>() {
        @Override
        protected Date initialValue() {
            return new Date();
        }
    };

    /**
     * Resolve hosts.
     */
    private boolean resolveHosts = false;

    /**
     * Are we doing conditional logging. default null.
     * It is the value of <code>conditionUnless</code> property.
     */
    protected String condition = null;

    /**
     * Are we doing conditional logging. default null.
     * It is the value of <code>conditionIf</code> property.
     */
    protected String conditionIf = null;


    /**
     * Don't log the request matches the patterns
     */
    protected String excludes = ".js,.css,jpg,.jpeg,.gif,.png,.bmp,.gif,.ico,.html,.htm,.woff2,.woff,/notification,/bower_components,/userSession,/spring_security_login,/accessLogs";

    protected String[] excludePatterns = {".js", ".css", ".jpeg", ".jpg", ".gif", ".png", ".bmp", ".gif", ".html", ".htm"};

    /**
     * MongoDB uri. See <a>http://api.mongodb.org/java/current/com/mongodb/MongoClientURI.html</a>
     */
    protected String uri;

    protected String host = "localhost";
    protected int port = 27017;
    protected String user;
    protected String pass;


    /**
     * Which db to store access logs. Default is tomcat
     */
    protected String dbName = "production";

    /**
     * Which Collection to store access logs. Default is tomcat_access_logs
     */
    protected String collName = "tomcat_access_logs";

    /**
     * Should we rotate our log collection? Default is true (like old behavior)
     * if true then use capped collection
     */
    protected boolean rotatable = false;

    /**
     * MongoDB collection's max size(in megabytes), Default is 1024
     */
    protected int capSize = 1024;

    /**
     * MongoDB collection instance
     */
    protected DBCollection coll = null;

    /**
     * Array of AccessLogElement, they will be used to make log message.
     */
    protected AccessLogElement[] logElements = null;

    /**
     * @see #setRequestAttributesEnabled(boolean)
     */
    protected boolean requestAttributesEnabled = false;

    /**
     * If true, then record the error page
     */
    protected boolean recordError = true;

    // ------------------------------------------------------------- Properties

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRequestAttributesEnabled(boolean requestAttributesEnabled) {
        this.requestAttributesEnabled = requestAttributesEnabled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean getRequestAttributesEnabled() {
        return requestAttributesEnabled;
    }

    /**
     * Return descriptive information about this implementation.
     */
    //@Override
    public String getInfo() {
        return (info);
    }


    /**
     * Return the format pattern.
     */
    public String getPattern() {
        return (this.pattern);
    }


    /**
     * Set the format pattern, first translating any recognized alias.
     *
     * @param pattern The new pattern
     */
    public void setPattern(String pattern) {
//        if (pattern == null) {
//            this.pattern = "";
//        } else if (pattern.equals(Constants.AccessLog.COMMON_ALIAS)) {
//            this.pattern = Constants.AccessLog.COMMON_PATTERN;
//        } else if (pattern.equals(Constants.AccessLog.COMBINED_ALIAS)) {
//            this.pattern = Constants.AccessLog.COMBINED_PATTERN;
//        } else if (pattern.equals(a2t.tomcat.valve.mongo.Constants.MongoAccessLog.DEFAULT_ALIAS)) {
//            this.pattern = a2t.tomcat.valve.mongo.Constants.MongoAccessLog.DEFAULT_PATTERN;
//        } else if (pattern.equals(a2t.tomcat.valve.mongo.Constants.MongoAccessLog.ALL_ALIAS)) {
//            this.pattern = a2t.tomcat.valve.mongo.Constants.MongoAccessLog.ALL_PATTERN;
//        } else {
//            this.pattern = pattern;
//        }
        this.pattern = pattern;
        logElements = createLogElements();
    }


    /**
     * Should we rotate the logs
     */
    public boolean isRotatable() {
        return rotatable;
    }


    /**
     * Set the value is we should we rotate the logs
     *
     * @param rotatable true is we should rotate.
     */
    public void setRotatable(boolean rotatable) {
        this.rotatable = rotatable;
    }


    /**
     * Set the resolve hosts flag.
     *
     * @param resolveHosts The new resolve hosts value
     * @deprecated Unused, removed in Tomcat 8.
     * See org.apache.catalina.connector.Connector.setEnableLookups(boolean).
     */
    @Deprecated
    public void setResolveHosts(boolean resolveHosts) {
        this.resolveHosts = resolveHosts;
    }


    /**
     * Get the value of the resolve hosts flag.
     *
     * @deprecated Unused, removed in Tomcat 8.
     * See org.apache.catalina.connector.Connector.setEnableLookups(boolean).
     */
    @Deprecated
    public boolean isResolveHosts() {
        return resolveHosts;
    }


    /**
     * Return whether the attribute name to look for when
     * performing conditional logging. If null, every
     * request is logged.
     */
    public String getCondition() {
        return condition;
    }


    /**
     * Set the ServletRequest.attribute to look for to perform
     * conditional logging. Set to null to log everything.
     *
     * @param condition Set to null to log everything
     */
    public void setCondition(String condition) {
        this.condition = condition;
    }


    /**
     * Return whether the attribute name to look for when
     * performing conditional logging. If null, every
     * request is logged.
     */
    public String getConditionUnless() {
        return getCondition();
    }


    /**
     * Set the ServletRequest.attribute to look for to perform
     * conditional logging. Set to null to log everything.
     *
     * @param condition Set to null to log everything
     */
    public void setConditionUnless(String condition) {
        setCondition(condition);
    }

    /**
     * Return whether the attribute name to look for when
     * performing conditional logging. If null, every
     * request is logged.
     */
    public String getConditionIf() {
        return conditionIf;
    }


    /**
     * Set the ServletRequest.attribute to look for to perform
     * conditional logging. Set to null to log everything.
     *
     * @param condition Set to null to log everything
     */
    public void setConditionIf(String condition) {
        this.conditionIf = condition;
    }

    // --------------------------------------------------------- Public Methods

    /**
     * Execute a periodic task, such as reloading, etc. This method will be
     * invoked inside the classloading context of this container. Unexpected
     * throwables will be caught and logged.
     */
    @Override
    public synchronized void backgroundProcess() {
        // do nothing
    }

    /**
     * Log a message summarizing the specified request and response, according
     * to the format specified by the <code>pattern</code> property.
     *
     * @param request  Request being processed
     * @param response Response being processed
     * @throws IOException      if an input/output error has occurred
     * @throws ServletException if a servlet error has occurred
     */
    @Override
    public void invoke(Request request, Response response) throws IOException,
            ServletException {
        getNext().invoke(request, response);
    }


    @Override
    public void log(Request request, Response response, long time) {
        if (!getState().isAvailable() || logElements == null
                || condition != null
                && null != request.getRequest().getAttribute(condition)
                || conditionIf != null
                && null == request.getRequest().getAttribute(conditionIf)) {
            return;
        }
        String uri = request.getRequestURI();
        if (uri != null) {
            for (String pattern : this.excludePatterns) {
                if (uri.contains(pattern)) {
                    return;
                }
            }
        }
        /**
         * XXX This is a bit silly, but we want to have start and stop time and
         * duration consistent. It would be better to keep start and stop
         * simply in the request and/or response object and remove time
         * (duration) from the interface.
         */
        long start = request.getCoyoteRequest().getStartTime();
        Date date = getDate(start + time);

        StringBuilder buf = new StringBuilder();
        DBObject result = new BasicDBObject(10);
        for (AccessLogElement logElement : logElements) {
            logElement.addElement(buf, result, date, request, response, time);
        }

        log(result);
    }


    // -------------------------------------------------------- Private Methods

    /**
     * Close the currently open mongodb connection (if any)
     */
    private synchronized void close() {
        if (this.coll == null) {
            return;
        }

        try {
            this.coll.getDB().getMongo().close();
        } catch (Exception ex) {
            log.error(sm.getString("a2tAccessLogValve.closeConnectionError"), ex);
        } finally {
            this.coll = null;
        }
    }


    /**
     * Log the specified message to the log file, switching files if the date
     * has changed since the previous log call.
     *
     * @param log Message to be logged
     */
    public void log(DBObject log) {
        /* In case something external rotated the file instead */
        synchronized (this) {
            open();
        }
        this.coll.insert(log, WriteConcern.UNACKNOWLEDGED);
    }


    /**
     * Open the new log file for the date specified by <code>dateStamp</code>.
     */
    protected synchronized void open() {
        if (coll != null) {
            return;
        }

        this.coll = MongoCollection.getOrCreateCollection(this.host, this.port, this.user, this.pass, this.uri, this.dbName, this.collName, this.rotatable, this.capSize, this.log, this.sm);


    }

    /**
     * This method returns a ThreadLocal Date object that is set to the
     * specified time. This saves creating a new Date object for every request.
     *
     * @return Date
     */
    private static Date getDate(long systime) {
        Date date = localDate.get();
        date.setTime(systime);
        return date;
    }

    /**
     * Find a locale by name
     */
    protected static Locale findLocale(String name, Locale fallback) {
        if (name == null || name.isEmpty()) {
            return Locale.getDefault();
        } else {
            for (Locale l : Locale.getAvailableLocales()) {
                if (name.equals(l.toString())) {
                    return (l);
                }
            }
        }
        log.error(sm.getString("a2tAccessLogValve.invalidLocale", name));
        return fallback;
    }


    /**
     * Start this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {
        open();
        setState(LifecycleState.STARTING);
    }


    /**
     * Stop this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    @Override
    protected synchronized void stopInternal() throws LifecycleException {
        setState(LifecycleState.STOPPING);
        close();
    }

    /**
     * AccessLogElement writes the partial message into the buffer.
     */
    protected interface AccessLogElement {
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time);

    }

    /**
     * write thread name - %I { "thread" : "xxx" }
     */
    protected static class ThreadNameElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            RequestInfo info = request.getCoyoteRequest().getRequestProcessor();
            if (info != null) {
                result.put("thread", info.getWorkerThreadName());
            } else {
                result.put("thread", "-");
            }
        }
    }

    /**
     * write local IP address - %A { "localIP" : "xxx" }
     */
    protected static class LocalAddrElement implements AccessLogElement {

        private static final String LOCAL_ADDR_VALUE;

        static {
            String init;
            try {
                init = InetAddress.getLocalHost().getHostAddress();
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                init = "127.0.0.1";
            }
            LOCAL_ADDR_VALUE = init;
        }

        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            result.put("localIP", LOCAL_ADDR_VALUE);
        }
    }

    /**
     * write remote IP address - %a { "remoteIP" : "xxx" }
     */
    protected class RemoteAddrElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (requestAttributesEnabled) {
                Object addr = request.getAttribute(REMOTE_ADDR_ATTRIBUTE);
                if (addr == null) {
                    result.put("remoteIP", request.getRemoteAddr());
                } else {
                    result.put("remoteIP", addr);
                }
            } else {
                result.put("remoteIP", request.getRemoteAddr());
            }
        }
    }

    /**
     * write remote host name - %h { "remoteHost" : "xxx" }
     */
    protected class HostElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            String value = null;
            if (requestAttributesEnabled) {
                Object host = request.getAttribute(REMOTE_HOST_ATTRIBUTE);
                if (host != null) {
                    value = host.toString();
                }
            }
            if (value == null || value.length() == 0) {
                value = request.getRemoteHost();
            }
            if (value == null || value.length() == 0) {
                value = "-";
            }
            result.put("remoteHost", value);
        }
    }

    /**
     * write remote logical username from identd (always returns '-') - %l { "user" : "xxx" }
     */
    protected static class LogicalUserNameElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            result.put("user", '-');
        }
    }

    /**
     * write request protocol - %H { "protocol" : "xxx" }
     */
    protected class ProtocolElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (requestAttributesEnabled) {
                Object proto = request.getAttribute(PROTOCOL_ATTRIBUTE);
                if (proto == null) {
                    result.put("protocol", request.getProtocol());
                } else {
                    result.put("protocol", proto);
                }
            } else {
                result.put("protocol", request.getProtocol());
            }
        }
    }

    /**
     * write remote user that was authenticated (if any), else '-' - %u { "remoteUser" : "xxx" }
     */
    protected static class UserElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (request != null) {
                String value = request.getRemoteUser();
                if (value != null) {
                    result.put("remoteUser", value);
                } else {
                    result.put("remoteUser", '-');
                }
            } else {
                result.put("remoteUser", '-');
            }
        }
    }

    /**
     * write date and time - %t or %t{format},  { "datetime" : "xxx" }
     */
    protected static class DateAndTimeElement implements AccessLogElement {

        /* Whether to use begin of request or end of response as the timestamp */
        private boolean usesBegin = false;

        protected DateAndTimeElement() {
            this(null);
        }

        protected DateAndTimeElement(String header) {
        }

        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            long timestamp = date.getTime();
            if (usesBegin) {
                timestamp -= time;
            }
            result.put("datetime", new Date(timestamp));
        }
    }

    /**
     * write first line of the request (method and request URI) - %r { "line1st" : "xxx" }
     */
    protected static class RequestElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (request != null) {
                String method = request.getMethod();
                if (method == null) {
                    // No method means no request line
                    buf.append('-');
                } else {
                    buf.append(request.getMethod());
                    buf.append(' ');
                    buf.append(request.getRequestURI());
                    if (request.getQueryString() != null) {
                        buf.append('?');
                        buf.append(request.getQueryString());
                    }
                    buf.append(' ');
                    buf.append(request.getProtocol());
                }
            } else {
                buf.append('-');
            }
            result.put("line1st", buf.toString());
            buf.delete(0, buf.length());
        }
    }

    /**
     * write HTTP status code of the response - %s { "statusCode" : "xxx" }
     */
    protected static class HttpStatusCodeElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (response != null) {
                result.put("statusCode", response.getStatus());
            } else {
                result.put("statusCode", '-');
            }
        }
    }

    /**
     * write local port on which this request was received - %p { "localPort" : xxx }
     */
    protected class LocalPortElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (requestAttributesEnabled) {
                Object port = request.getAttribute(SERVER_PORT_ATTRIBUTE);
                if (port == null) {
                    result.put("localPort", request.getServerPort());
                } else {
                    result.put("localPort", port);
                }
            } else {
                result.put("localPort", request.getServerPort());
            }
        }
    }

    /**
     * write bytes sent, excluding HTTP headers - %b, %B { "bytesSent" : xxx }
     */
    protected static class ByteSentElement implements AccessLogElement {
        private final boolean conversion;

        /**
         * if conversion is true, write '-' instead of 0 - %b
         */
        public ByteSentElement(boolean conversion) {
            this.conversion = conversion;
        }

        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            // Don't need to flush since trigger for log message is after the
            // response has been committed
            long length = response.getBytesWritten(false);
            if (length <= 0) {
                // Protect against nulls and unexpected types as these values
                // may be set by untrusted applications
                Object start = request.getAttribute(
                        Globals.SENDFILE_FILE_START_ATTR);
                if (start instanceof Long) {
                    Object end = request.getAttribute(
                            Globals.SENDFILE_FILE_END_ATTR);
                    if (end instanceof Long) {
                        length = ((Long) end).longValue() -
                                ((Long) start).longValue();
                    }
                }
            }
            if (length <= 0 && conversion) {
                result.put("bytesSent", '-');
            } else {
                result.put("bytesSent", length);
            }
        }
    }

    /**
     * write request method (GET, POST, etc.) - %m { "method" : "xxx" }
     */
    protected static class MethodElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (request != null) {
                result.put("method", request.getMethod());
            }
        }
    }

    /**
     * write time taken to process the request - %D, %T { "elapsedMillis" : xxx } { "elapsedSeconds" : xxx }
     */
    protected static class ElapsedTimeElement implements AccessLogElement {
        private final boolean millis;

        /**
         * if millis is true, write time in millis - %D
         * if millis is false, write time in seconds - %T
         */
        public ElapsedTimeElement(boolean millis) {
            this.millis = millis;
        }

        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (millis) {
                result.put("elapsedMillis", time);
            } else {
                // second
                buf.append(time / 1000);
                buf.append('.');
                int remains = (int) (time % 1000);
                buf.append(remains / 100);
                remains = remains % 100;
                buf.append(remains / 10);
                buf.append(remains % 10);
                result.put("elapsedSeconds", Double.valueOf(buf.toString()));
                buf.delete(0, buf.length());
            }
        }
    }

    /**
     * write time until first byte is written (commit time) in millis - %F { "commitTime" : xxx }
     */
    protected static class FirstByteTimeElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            long commitTime = response.getCoyoteResponse().getCommitTime();
            if (commitTime == -1) {
                result.put("commitTime", '-');
            } else {
                long delta =
                        commitTime - request.getCoyoteRequest().getStartTime();
                result.put("commitTime", delta);
            }
        }
    }

    /**
     * write Query string (prepended with a '?' if it exists) - %q { "queryString" : "xxx" }
     */
    protected static class QueryElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            String query = null;
            if (request != null) {
                query = request.getQueryString();
            }
            if (query != null) {
                result.put("queryString", query);
            }
        }
    }

    /**
     * write All Parameters - %P { "params" : { "param1" : "xxx", "param2: "xxx" } }<br>
     * . will be replaced to $ if parameter name contains dot
     */
    protected static class RequestParametersElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            Enumeration<String> paramNames = request.getParameterNames();
            if (!paramNames.hasMoreElements()) {
                return;
            }
            DBObject params = new BasicDBObject();
            while (paramNames.hasMoreElements()) {
                String paramName = paramNames.nextElement();
                String replaced = paramName.replace('.', '$');
                String[] values = request.getParameterValues(paramName);
                if (values.length == 0) {
                    continue;
                } else if (values.length == 1) {
                    params.put(replaced, values[0]);
                } else {
                    params.put(replaced, values);
                }
            }
            result.put("params", params);
        }
    }

    /**
     * Record error message to { "error" : "xxxxx" } if error happends<br>
     * Code piece is copied from <code>org.apache.catalina.valves.ErrorReportValve</code>
     *
     * @author qianjia
     */
    protected static class ErrorRecordElement implements AccessLogElement {

        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request, Response response, long time) {

            Throwable throwable = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            try {
                report(buf, result, request, response, throwable);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            } finally {
                buf.delete(0, buf.length());
            }

        }

        /**
         * Prints out an error report.
         *
         * @param request   The request being processed
         * @param response  The response being generated
         * @param throwable The exception that occurred (which possibly wraps
         *                  a root cause exception
         */
        protected void report(StringBuilder sb, DBObject result, Request request, Response response, Throwable throwable) {

            // Do nothing on non-HTTP responses
            int statusCode = response.getStatus();

            // Do nothing on a 1xx, 2xx and 3xx status and throwable is null
            if (throwable == null && statusCode < 400) {
                return;
            }

            String message = RequestUtil.filter(response.getMessage());
            if (message == null) {
                if (throwable != null) {
                    String exceptionMessage = throwable.getMessage();
                    if (exceptionMessage != null && exceptionMessage.length() > 0) {
                        message = RequestUtil.filter(
                                (new Scanner(exceptionMessage)).nextLine());
                    }
                }
                if (message == null) {
                    message = "";
                }
            }

            // Do nothing if there is no report for the specified status code and
            // no error message provided
            String report = null;
            StringManager smClient = StringManager.getManager(Constants.Package, request.getLocales());
            try {
                report = smClient.getString("http." + statusCode);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
            }
            if (report == null) {
                if (message.length() == 0) {
                    return;
                } else {
                    report = smClient.getString("errorReportValve.noDescription");
                }
            }

            sb.append(smClient.getString("errorReportValve.statusHeader", String.valueOf(statusCode), message)).append("\n");
            sb.append("type ");
            if (throwable != null) {
                sb.append(smClient.getString("errorReportValve.exceptionReport"));
            } else {
                sb.append(smClient.getString("errorReportValve.statusReport"));
            }
            sb.append("\n");
            sb.append(smClient.getString("errorReportValve.message")).append(": ");
            sb.append(message).append("\n");
            sb.append(smClient.getString("errorReportValve.description")).append(": ");
            sb.append(report).append("\n");
            if (throwable != null) {
                String stackTrace = getStackTraces(throwable);
                sb.append(RequestUtil.filter(stackTrace)).append("\n");

                int loops = 0;
                Throwable rootCause = throwable.getCause();
                while (rootCause != null && (loops < 10)) {
                    stackTrace = getStackTraces(rootCause);
                    sb.append(smClient.getString("errorReportValve.rootCause")).append("\n");
                    sb.append(RequestUtil.filter(stackTrace)).append("\n");
                    // In case root cause is somehow heavily nested
                    rootCause = rootCause.getCause();
                    loops++;
                }
            }

            result.put("error", sb.toString());
        }

        /**
         * Print out a partial servlet stack trace (truncating at the last
         * occurrence of javax.servlet.).
         */
        protected String getStackTraces(Throwable t) {
            StringBuilder trace = new StringBuilder();
            trace.append(t.toString()).append('\n');
            StackTraceElement[] elements = t.getStackTrace();
            for (int i = 0; i < elements.length; i++) {
                trace.append('\t').append(elements[i].toString()).append('\n');
            }
            return trace.toString();
        }
    }

    /**
     * write user session ID - %S { "sessionId" : "xxx" }
     */
    protected static class SessionIdElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (request == null) {
                result.put("sessionId", '-');
            } else {
                Session session = request.getSessionInternal(false);
                if (session == null) {
                    result.put("sessionId", '-');
                } else {
                    result.put("sessionId", session.getIdInternal());
                }
            }
        }
    }

    /**
     * write requested URL path - %U { "url" : "xxx" }
     */
    protected static class RequestURIElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (request != null) {
                result.put("url", request.getRequestURI());
            } else {
                result.put("url", '-');
            }
        }
    }

    /**
     * write local server name - %v { "serverName" : "xxx" }
     */
    protected static class LocalServerNameElement implements AccessLogElement {
        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            result.put("serverName", request.getServerName());
        }
    }

    /**
     * write any string
     */
    protected static class NoopElement implements AccessLogElement {

        public static final NoopElement INSTANCE = new NoopElement();

        public NoopElement() {
        }

        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
        }
    }

    /**
     * write incoming headers - %{xxx}i  {"requestHeaders" : { "header1" : "xxx", "header2" : "xxx"} }
     */
    protected static class HeaderElement implements AccessLogElement {
        private final String header;

        public HeaderElement(String header) {
            this.header = header;
        }

        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            Enumeration<String> iter = request.getHeaders(header);
            if (iter.hasMoreElements()) {
                buf.append(iter.nextElement());
                while (iter.hasMoreElements()) {
                    buf.append(',').append(iter.nextElement());
                }
            } else {
                buf.append('-');
            }
            DBObject headers = (DBObject) result.get("requestHeaders");
            if (headers == null) {
                headers = new BasicDBObject();
                result.put("requestHeaders", headers);
            }
            headers.put(header, buf.toString());
            buf.delete(0, buf.length());
        }
    }

    /**
     * write a specific cookie - %{xxx}c {"cookies" : { "cookie1" : "xxx", "cookie2" : "xxx"} }
     */
    protected static class CookieElement implements AccessLogElement {
        private final String header;

        public CookieElement(String header) {
            this.header = header;
        }


        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {

            String value = "-";
            Cookie[] c = request.getCookies();
            if (c != null) {

                for (int i = 0; i < c.length; i++) {

                    if (header.equals(c[i].getName())) {
                        value = c[i].getValue();
                        break;
                    }
                }
            }
            DBObject cookies = (DBObject) result.get("cookies");
            if (cookies == null) {
                cookies = new BasicDBObject();
                result.put("cookies", cookies);
            }

            if (header.equalsIgnoreCase("user")) {
                result.put("a2tUser", value.replace("%22", ""));
            } else {
                cookies.put(header, value.replace("%22", ""));
            }

        }
    }

    /**
     * write a specific response header - %{xxx}o {"responseHeaders" : { "header1" : "xxx", "header2" : "xxx"} }
     */
    protected static class ResponseHeaderElement implements AccessLogElement {
        private final String header;

        public ResponseHeaderElement(String header) {
            this.header = header;
        }

        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            if (null != response) {
                Iterator<String> iter = response.getHeaders(header).iterator();
                if (iter.hasNext()) {
                    buf.append(iter.next());
                    while (iter.hasNext()) {
                        buf.append(',').append(iter.next());
                    }
                } else {
                    buf.append('-');
                }
            } else {
                buf.append('-');
            }
            DBObject headers = (DBObject) result.get("responseHeaders");
            if (headers == null) {
                headers = new BasicDBObject();
                result.put("responseHeaders", headers);
            }
            headers.put(header, buf.toString());
            buf.delete(0, buf.length());
        }
    }

    /**
     * write an attribute in the ServletRequest - %{xxx}r  {"requestAttrs" : { "attr1" : "xxx", "attr2" : "xxx"} }
     */
    protected static class RequestAttributeElement implements AccessLogElement {
        private final String header;

        public RequestAttributeElement(String header) {
            this.header = header;
        }

        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            Object value = null;
            if (request != null) {
                value = request.getAttribute(header);
            } else {
                value = "??";
            }
            if (value != null) {
                if (value instanceof String) {
                    buf.append((String) value);
                } else {
                    buf.append(value.toString());
                }
            } else {
                buf.append('-');
            }

            DBObject headers = (DBObject) result.get("requestAttrs");
            if (headers == null) {
                headers = new BasicDBObject();
                result.put("requestAttrs", headers);
            }
            headers.put(header, buf.toString());
            buf.delete(0, buf.length());
        }
    }

    /**
     * write an attribute in the HttpSession - %{xxx}s  {"sessionAttrs" : { "attr1" : "xxx", "attr2" : "xxx"} }
     */
    protected static class SessionAttributeElement implements AccessLogElement {
        private final String header;

        public SessionAttributeElement(String header) {
            this.header = header;
        }

        @Override
        public void addElement(StringBuilder buf, DBObject result, Date date, Request request,
                               Response response, long time) {
            Object value = null;
            if (null != request) {
                HttpSession sess = request.getSession(false);
                if (null != sess) {
                    value = sess.getAttribute(header);
                }
            } else {
                value = "??";
            }
            if (value != null) {
                if (value instanceof String) {
                    buf.append((String) value);
                } else {
                    buf.append(value.toString());
                }
            } else {
                buf.append('-');
            }

            DBObject headers = (DBObject) result.get("sessionAttrs");
            if (headers == null) {
                headers = new BasicDBObject();
                result.put("sessionAttrs", headers);
            }
            headers.put(header, buf.toString());
            buf.delete(0, buf.length());
        }
    }


    /**
     * parse pattern string and create the array of AccessLogElement
     */
    protected AccessLogElement[] createLogElements() {
        List<AccessLogElement> list = new ArrayList<AccessLogElement>();
        boolean replace = false;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (replace) {
                /*
                 * For code that processes {, the behavior will be ... if I do
                 * not encounter a closing } - then I ignore the {
                 */
                if ('{' == ch) {
                    StringBuilder name = new StringBuilder();
                    int j = i + 1;
                    for (; j < pattern.length() && '}' != pattern.charAt(j); j++) {
                        name.append(pattern.charAt(j));
                    }
                    if (j + 1 < pattern.length()) {
                        /* the +1 was to account for } which we increment now */
                        j++;
                        list.add(createAccessLogElement(name.toString(),
                                pattern.charAt(j)));
                        i = j; /* Since we walked more than one character */
                    } else {
                        // D'oh - end of string - pretend we never did this
                        // and do processing the "old way"
                        list.add(createAccessLogElement(ch));
                    }
                } else {
                    list.add(createAccessLogElement(ch));
                }
                replace = false;
            } else if (ch == '%') {
                replace = true;
                list.add(NoopElement.INSTANCE);
                buf = new StringBuilder();
            } else {
                buf.append(ch);
            }
        }
        if (this.recordError) {
            list.add(new ErrorRecordElement());
        }
        if (buf.length() > 0) {
            list.add(new NoopElement());
        }
        return list.toArray(new AccessLogElement[0]);
    }

    /**
     * create an AccessLogElement implementation which needs header string
     */
    protected AccessLogElement createAccessLogElement(String header, char pattern) {
        switch (pattern) {
            case 'i':
                return new HeaderElement(header);
            case 'c':
                return new CookieElement(header);
            case 'o':
                return new ResponseHeaderElement(header);
            case 'r':
                return new RequestAttributeElement(header);
            case 's':
                return new SessionAttributeElement(header);
            case 't':
                return new DateAndTimeElement(header);
            default:
                return NoopElement.INSTANCE;
        }
    }

    /**
     * create an AccessLogElement implementation
     */
    protected AccessLogElement createAccessLogElement(char pattern) {
        switch (pattern) {
            case 'a':
                return new RemoteAddrElement();
            case 'A':
                return new LocalAddrElement();
            case 'b':
                return new ByteSentElement(true);
            case 'B':
                return new ByteSentElement(false);
            case 'D':
                return new ElapsedTimeElement(true);
            case 'F':
                return new FirstByteTimeElement();
            case 'h':
                return new HostElement();
            case 'H':
                return new ProtocolElement();
            case 'l':
                return new LogicalUserNameElement();
            case 'm':
                return new MethodElement();
            case 'p':
                return new LocalPortElement();
            case 'q':
                return new QueryElement();
            case 'r':
                return new RequestElement();
            case 's':
                return new HttpStatusCodeElement();
            case 'S':
                return new SessionIdElement();
            case 't':
                return new DateAndTimeElement();
            case 'T':
                return new ElapsedTimeElement(false);
            case 'u':
                return new UserElement();
            case 'U':
                return new RequestURIElement();
            case 'v':
                return new LocalServerNameElement();
            case 'I':
                return new ThreadNameElement();
            case 'P':
                return new RequestParametersElement();
            default:
                return NoopElement.INSTANCE;
        }
    }

    public void setCapSize(int capSize) {
        this.capSize = capSize;
    }

    public void setCollName(String collName) {
        this.collName = collName;
    }

    public String getCollName() {
        return collName;
    }

    public void setExcludes(String excludes) {
        if (excludes == null) {
            return;
        }
        this.excludes = excludes;
        this.excludePatterns = excludes.split(",");
    }

    public void setRecordError(boolean recordError) {
        this.recordError = recordError;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUser() {
        return user;
    }

    public String getPass() {
        return pass;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public void setPass(String pass) {
        this.pass = pass;
    }


}
