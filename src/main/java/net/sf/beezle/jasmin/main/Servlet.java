/**
 * Copyright 1&1 Internet AG, http://www.1and1.org
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.beezle.jasmin.main;

import net.sf.beezle.jasmin.model.Engine;
import net.sf.beezle.jasmin.model.File;
import net.sf.beezle.jasmin.model.Module;
import net.sf.beezle.jasmin.model.Resolver;
import net.sf.beezle.jasmin.model.Source;
import net.sf.beezle.sushi.fs.Node;
import net.sf.beezle.sushi.fs.World;
import net.sf.beezle.sushi.fs.file.FileNode;
import net.sf.beezle.sushi.fs.webdav.WebdavFilesystem;
import net.sf.beezle.sushi.fs.webdav.WebdavNode;
import net.sf.beezle.sushi.util.Strings;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONWriter;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Servlet extends HttpServlet {
    public static final Logger LOG = Logger.getLogger(Servlet.class);

    private static final String HOSTNAME = getHostname();

    private static String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            LOG.error("unknown hostname", e);
            return "unknown";
        }
    }

    // re-create engine if one of these files was changed; null if life resolving is off
    private List<Node> reloadFiles;
    private long loaded;
    private long otherVmStartupDate;

    private FileNode docroot;
    private Application application;

    // lazy init, because I need a URL first:
    private Engine engine;

    public Servlet() {
        // NOT longer than 10 years because the date format has only 2 digits for the year.
        this.otherVmStartupDate = VM_STARTUP_DATE.getTime() - TEN_YEARS;
    }

    /** creates configuration. */
    @Override
    public void init(ServletConfig config) throws ServletException {
        World world;
        String str;

        try {
            world = new World();
            configure(world, "http");
            configure(world, "https");
            str = config.getInitParameter("docroot");
            docroot = Application.file(world, str != null ? str : config.getServletContext().getRealPath(""));
            docroot.checkDirectory();
            LOG.info("home: " + world.getHome());
            application = Application.load(world, config, docroot);
            LOG.info("docroot: " + docroot);
        } catch (RuntimeException e) {
            error(null, "init", e);
            throw e;
        } catch (Exception e) {
            error(null, "init", e);
            throw new ServletException(e);
        } catch (Error e) {
            error(null, "init", e);
            throw e;
        } catch (Throwable e) {
            error(null, "init", e);
            throw new RuntimeException("unexpected throwable", e);
        }
    }

    private static final int HTTP_TIMEOUT = 10 * 1000;

    private static void configure(World world, String scheme) {
        WebdavFilesystem webdav;

        webdav = world.getFilesystem(scheme, WebdavFilesystem.class);
        webdav.setDefaultConnectionTimeout(HTTP_TIMEOUT);
        webdav.setDefaultReadTimeout(HTTP_TIMEOUT);
    }

    /** Creates engine from configuration and resolve. Sychronized ensures we initialize only once. */
    private synchronized void lazyInit(HttpServletRequest request) throws IOException {
        List<File> files;
        URL url;
        long lastModified;
        long now;
        Resolver resolver;
        Node localhost;
        FileNode file;
        Object[] tmp;

        resolver = application.resolver;
        if (engine != null && resolver.isLife()) {
            for (Node node : reloadFiles) {
                lastModified = node.getLastModified();
                if (lastModified > loaded) {
                    now = System.currentTimeMillis();
                    if (lastModified > now) {
                        // fail to avoid repeated re-init
                        throw new IOException(node.getURI() + " has lastModifiedDate in the future: "
                                + new Date(lastModified) + "(now: " + new Date(now) + ")");
                    }
                    LOG.info("reloading jasmin for application '" + application.getName() + "' - changed file: " + node);
                    engine = null;
                    resolver.reset();
                }
            }
        }
        if (engine == null) {
            url = new URL(request.getRequestURL().toString());
            try {
                // always use http, avoid https
                localhost = resolver.getWorld().node(new URI("http", null, url.getHost(), url.getPort(), "", null, null));
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
            tmp = application.createEngine(docroot, localhost);
            engine = (Engine) tmp[0];
            LOG.info("started engine, initial url=" + url);
            for (Module module : engine.repository.modules()) {
                files = module.files();
                if (files.size() > 1) {
                    LOG.warn("deprecated: module '" + module.getName() + "' contains more than 1 file: " + files);
                }
                for (File f : files) {
                    if (f.getNormal() instanceof WebdavNode) {
                        LOG.warn("deprecated: module '" + module.getName() + "' uses base LOCALHOST: " + f.getNormal().getURI());
                    }
                }
            }
            if (resolver.isLife()) {
                reloadFiles = (List<Node>) tmp[1];
                file = resolver.getLiveXml();
                if (file != null) {
                    reloadFiles.add(file);
                }
                LOG.info("reload if one of these " + reloadFiles.size() + " files is modified: ");
                for (Node node : reloadFiles) {
                    LOG.info("  " + node.getURI());
                }
                loaded = System.currentTimeMillis();
            }
            if (engine == null) {
                throw new IllegalStateException();
            }
        }
    }

    //--

    /**
     * Called by the servlet engine to process get requests:
     * a) to set the Last-Modified header in the response
     * b) to check if 304 can be redurned if the "if-modified-since" request header is present
     * @return -1 for when unknown
     */
    @Override
    public long getLastModified(HttpServletRequest request) {
        String path;
        int idx;
        long result;

        result = -1;
        try {
            path = request.getPathInfo();
            if (path != null && path.startsWith("/get/")) {
                lazyInit(request);
                path = path.substring(5);
                idx = path.indexOf('/');
                if (idx != -1) {
                    path = path.substring(idx + 1);
                    result = engine.getLastModified(path);
                }
            }
        } catch (IOException e) {
            error(request, "getLastModified", e);
            // fall-through
        } catch (RuntimeException e) {
            error(request, "getLastModified", e);
            throw e;
        } catch (Error e) {
            error(request, "getLastModified", e);
            throw e;
        } catch (Throwable e) {
            error(request, "getLastModified", e);
            throw new RuntimeException("unexpected throwable", e);
        }
        LOG.debug("getLastModified(" + request.getPathInfo() + ") -> " + result);
        return result;
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        try {
            doGetUnchecked(request, response);
        } catch (IOException e) {
            // I can't compile against this class because the servlet api does not officially
            // report this situation ...
            // See http://tomcat.apache.org/tomcat-5.5-doc/catalina/docs/api/org/apache/catalina/connector/ClientAbortException.html
            if (e.getClass().getName().equals("org.apache.catalina.connector.ClientAbortException")) {
                // this is not an error: the client browser closed the response stream, e.g. because
                // the user already left the page
                LOG.info("aborted by client", e);
            } else {
                error(request, "get", e);
            }
            throw e;
        } catch (RuntimeException e) {
            error(request, "get", e);
            throw e;
        } catch (Error e) {
            error(request, "get", e);
            throw e;
        } catch (Throwable e) {
            error(request, "get", e);
            throw new RuntimeException("unexpected throwable", e);
        }
    }

    private static final String MODULE_PREFIX = "/admin/module/";

    private void doGetUnchecked(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path;

        path = request.getPathInfo();
        if (path == null) {
            response.sendRedirect(request.getContextPath() + request.getServletPath() + "/");
            return;
        }
        lazyInit(request);
        LOG.debug("get " + path);
        if (path.startsWith("/get/")) {
            get(request, response, path.substring(5));
            return;
        }
        if (path.equals("/admin/")) {
            main(response);
            return;
        }
        if (path.equals("/admin/repository")) {
            repository(request, response);
            return;
        }
        if (path.equals("/admin/hashCache")) {
            hashCache(response);
            return;
        }
        if (path.equals("/admin/contentCache")) {
            contentCache(response);
            return;
        }
        if (path.startsWith(MODULE_PREFIX)) {
            module(request, response, path.substring(MODULE_PREFIX.length()));
            return;
        }
        if (path.equals("/admin/reload")) {
            reload(response);
            return;
        }
        if (path.equals("/admin/check")) {
            fileCheck(response);
            return;
        }
        notFound(request, response);
    }

    private static final long FIVE_MINUTES = 1000L * 60 * 5;
    private static final long SEVEN_DAYS = 1000L * 3600 * 24 * 7;
    private static final long TEN_YEARS = 1000L * 3600 * 24 * 365 * 10;

    private void get(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        String version;
        boolean expire;
        int idx;
        long started;
        long duration;
        int bytes;
        boolean gzip;
        long date;

        idx = path.indexOf('/');
        if (idx == -1) {
            notFound(request, response);
            return;
        }
        started = System.currentTimeMillis();
        version = path.substring(0, idx);
        expire = !"no-expires".equals(version);
        if (expire && !VM_STARTUP_STR.equals(version)) {
            try {
                synchronized (FMT) {
                    date = FMT.parse(version).getTime();
                }
            } catch (ParseException e) {
                notFound(request, response);
                return;
            }
            if (sameTime(VM_STARTUP, date) || sameTime(otherVmStartupDate, date)) {
                // ok
            } else if (date > otherVmStartupDate) {
                otherVmStartupDate = date;
            } else {
                // usually, otherVmStartupDate is smaller, but after switching back, VM_STARTUP will be smaller
                if (Math.min(otherVmStartupDate, VM_STARTUP) - date > SEVEN_DAYS) {
                    gone(request, response);
                    return;
                }
            }
        }
        path = path.substring(idx + 1);
        if (application.resolver.isLife()) {
            // unknown headers are ok: see http://tools.ietf.org/html/rfc2616#section-7.1
            response.addHeader("Hi", "Sie werden bedient von Jasmin, vielen Dank fuer ihren Request!");
        }
        checkCharset(request.getHeader("Accept-Charset"));
        if (expire && application.expires != null) {
            response.setDateHeader("Expires", started + 1000L * application.expires);
            response.addHeader("Cache-Control", "max-age=" + application.expires);
        }
        gzip = canGzip(request);
        bytes = engine.process(path, response, gzip);
        duration = System.currentTimeMillis() - started;
        LOG.info(path + "|" + bytes + "|" + duration + "|" + gzip + "|" + referer(request));
    }

    private static boolean sameTime(long left, long right) {
        long diff;

        diff = left - right;
        if (diff < 0) {
            diff = -diff;
        }
        return diff < FIVE_MINUTES;
    }

    private static boolean canGzip(HttpServletRequest request) {
        String accepted;
        String userAgent;

        accepted = request.getHeader("Accept-Encoding");
        if (accepted == null) {
            return false;
        }
        if (!contains(accepted, "gzip")) {
            return false;
        }
        userAgent = request.getHeader("User-Agent");
        if (userAgent == null || !whiteListed(userAgent)) {
            LOG.info("user-agent not white-listed for gzip: " + userAgent);
            return false;
        }
        return true;
    }

    // see http://msdn.microsoft.com/en-us/repository/ms537503(VS.85).aspx
    private static final Pattern MSIE = Pattern.compile("Mozilla/4.0 \\(compatible; MSIE (\\d+)\\..*");

    private static final Pattern MOZILLA = Pattern.compile("Mozilla/(\\d+)\\..*");

    public static boolean whiteListed(String str) {
        if (atLeast(str, MSIE, 7)) {
            return true;
        }
        if (atLeast(str, MOZILLA, 5)) {
            return true;
        }
        return false;
    }

    private static boolean atLeast(String str, Pattern pattern, int num) {
        String version;
        Matcher matcher;

        matcher = pattern.matcher(str);
        if (matcher.matches()) {
            version = matcher.group(1);
            return Integer.parseInt(version) >= num;
        }
        return false;
    }

    // see http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html
    public static void checkCharset(String accepts) throws IOException {
        if (accepts == null) {
            // ie7 does not send this header
            return;
        }
        // I've seen both "utf-8" and "UTF-8" -> test case-insensitive
        if (contains(accepts.toLowerCase(), "utf-8")) {
            return;
        }
        if (contains(accepts, "*")) {
            return;
        }
        throw new IOException("utf-8 is not accepted: " + accepts);
    }

    // see http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html
    public static boolean contains(String list, String keyword) {
        int idx;
        int colon;
        String quality;

        idx = list.indexOf(keyword);
        if (idx == -1) {
            return false;
        }
        idx += keyword.length();
        colon = list.indexOf(",", idx);
        if (colon == -1) {
            colon = list.length();
        }
        quality = list.substring(idx, colon);
        idx = quality.indexOf('=');
        if (idx == -1) {
            return true;
        }
        return !"0".equals(quality.substring(idx + 1).trim());
    }

    private void main(HttpServletResponse response) throws IOException {
        html(response,
                "<p>Jasmin Servlet " + getVersion() + "</p>",
                "<p>Hostname: " + HOSTNAME + "</p>",
                "<p>Docroot: " + docroot.getAbsolute() + "</p>",
                "<p>VM Startup: " + VM_STARTUP_STR + "</p>",
                "<p>Other VM Startup: " + FMT.format(otherVmStartupDate) + "</p>",
                "<p>Loaded: " + new Date(loaded) + "</p>",
                "<p>HashCache: " + engine.hashCache.getMaxSize() + "</p>",
                "<p>ContentCache: " + engine.contentCache.getMaxSize() + "</p>",
                application.resolver.isLife() ? "<a href='reload'>Reload Files</a>" : "(no reload)",
                "<a href='repository'>Repository</a>",
                "<a href='hashCache'>Hash Cache</a>",
                "<a href='contentCache'>Content Cache</a>",
                "<a href='check'>File Check</a>");
    }

    private String getVersion() {
        return getClass().getPackage().getImplementationVersion();
    }

    private void repository(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Writer writer;
        JSONWriter dest;
        List<Module> modules;

        response.setContentType("application/json");
        writer = response.getWriter();
        dest = new JSONWriter(writer);
        try {
            dest.array();
            modules = new ArrayList<Module>(engine.repository.modules());
            Collections.sort(modules, new Comparator<Module>() {
                @Override
                public int compare(Module left, Module right) {
                    return left.getName().compareTo(right.getName());
                }
            });
            for (Module module : modules) {
                dest.object();
                dest.key("name");
                dest.value(module.getName());
                dest.key("details");
                dest.value(Strings.removeRight(request.getRequestURL().toString(), "/repository") + "/module/" + module.getName());
                dest.endObject();
            }
            dest.endArray();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        writer.close();
    }

    private void hashCache(HttpServletResponse response) throws IOException {
        text(response, engine.hashCache.toString());
    }

    private void contentCache(HttpServletResponse response) throws IOException {
        text(response, engine.contentCache.toString());
    }


    private void module(HttpServletRequest request, HttpServletResponse response, String name) throws IOException {
        Writer writer;
        JSONWriter dest;
        Module module;
        Source source;

        module = engine.repository.lookup(name);
        source = module.getSource();
        if (module == null) {
            notFound(request, response);
            return;
        }
        response.setContentType("application/json");
        writer = response.getWriter();
        dest = new JSONWriter(writer);
        try {
            dest.object();
            dest.key("name");
            dest.value(module.getName());
            dest.key("files");
            dest.array();
            for (File file : module.files()) {
                dest.object();
                dest.key("type");
                dest.value(file.getType());
                dest.key("normal");
                dest.value(file.getNormal().getURI());
                if (file.getMinimized() != null) {
                    dest.key("minimized");
                    dest.value(file.getMinimized().getURI());
                }
                dest.key("variant");
                dest.value(file.getVariant());
                dest.endObject();
            }
            dest.endArray();
            dest.key("dependencies");
            dest.array();
            for (Module dependency : module.dependencies()) {
                dest.value(dependency.getName());
            }
            dest.endArray();
            dest.key("source");
            dest.object();
            dest.key("artifactId");
            dest.value(source.artifactId);
            dest.key("groupId");
            dest.value(source.groupId);
            dest.key("version");
            dest.value(source.version);
            dest.key("scm");
            dest.value(source.scm);
            dest.endObject();
            dest.endObject();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        writer.close();
    }

    private void reload(HttpServletResponse response) throws IOException {
        String[] lines;

        lines = new String[reloadFiles.size()];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = reloadFiles.get(i).getURI().toString();
        }
        text(response, lines);
    }

    private void fileCheck(HttpServletResponse response) throws IOException {
        FileCheck check;

        check = new FileCheck();
        check.minimize(true, engine.repository, application.resolver.getWorld());
        text(response, check.toString());
    }

    private void text(HttpServletResponse response, String... lines) throws IOException {
        Writer writer;

        response.setContentType("text/plain");
        writer = response.getWriter();
        for (String line : lines) {
            writer.write(line);
            writer.write('\n');
        }
        writer.close();
    }

    private void html(HttpServletResponse response, String... lines) throws IOException {
        Writer writer;

        response.setContentType("text/html");
        writer = response.getWriter();
        writer.write("<html><header></header><body>\n");
        for (String line : lines) {
            writer.write(line);
            writer.write('\n');
        }
        writer.write("</body>");
        writer.close();
    }

    private void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        LOG.warn("not found: " + request.getPathInfo());
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private void gone(HttpServletRequest request, HttpServletResponse response) throws IOException {
        LOG.warn("gone: " + request.getPathInfo());
        response.sendError(HttpServletResponse.SC_GONE);
    }

    //--

    /** @param request may be null */
    private void error(HttpServletRequest request, String method, Throwable throwable) {
        /* TODO: LogMessage message;
        Enumeration<String> names;
        Enumeration<String> values;
        String name;
        String value;

        message = new LogMessage();
        message.setServlet("jasmin");
        message.setSubject(method + ":" + throwable.getMessage());
        if (request != null) {
            message.setPage(referer(request));
            message.setUri(pathInfo(request));
            names = request.getHeaderNames();
            while (names.hasMoreElements()) {
                name = names.nextElement();
                values = request.getHeaders(name);
                while (values.hasMoreElements()) {
                    value = values.nextElement();
                    message.getAdditionalValues().put("header " + name, value);
                }
            }
            // currently unused:  message.setBody()
        }*/
        LOG.error(method + ":" + throwable.getMessage(), throwable);
    }

    private static String pathInfo(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return request.getPathInfo();
    }
    private static String referer(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return request.getHeader("Referer");
    }

    //-- XSLT functions

    public static final SimpleDateFormat FMT = new SimpleDateFormat("yyMMdd-HHmm");
    public static final Date VM_STARTUP_DATE = new Date();
    public static final long VM_STARTUP = VM_STARTUP_DATE.getTime();
    public static final String VM_STARTUP_STR = FMT.format(VM_STARTUP_DATE);

    public static String getVmStartup() {
        return VM_STARTUP_STR;
    }
}
