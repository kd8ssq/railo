package railo.runtime.net.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpSession;

import railo.commons.io.IOUtil;
import railo.commons.lang.Pair;
import railo.commons.lang.StringUtil;
import railo.commons.net.URLItem;
import railo.runtime.PageContext;
import railo.runtime.engine.ThreadLocalPageContext;
import railo.runtime.op.Caster;
import railo.runtime.op.date.DateCaster;
import railo.runtime.type.Collection;
import railo.runtime.type.KeyImpl;
import railo.runtime.type.dt.DateTime;
import railo.runtime.type.scope.Form;
import railo.runtime.type.scope.FormImpl;
import railo.runtime.type.scope.URL;
import railo.runtime.type.scope.URLImpl;
import railo.runtime.type.scope.UrlFormImpl;
import railo.runtime.type.scope.util.ScopeUtil;
import railo.runtime.type.util.ArrayUtil;
import railo.runtime.type.util.CollectionUtil;
import railo.runtime.util.EnumerationWrapper;

/**
 * extends a existing {@link HttpServletRequest} with the possibility to reread the input as many you want.
 */
public final class HTTPServletRequestWrap implements HttpServletRequest,Serializable {


	private boolean firstRead=true;
	private byte[] barr;
	private static final int MIN_STORAGE_SIZE=1*1024*1024;
	private static final int MAX_STORAGE_SIZE=50*1024*1024;
	private static final int SPACIG=1024*1024;
	
	private String servlet_path;
	private String request_uri;
	private String context_path;
	private String path_info;
	private String query_string;
	private boolean disconnected;
	private HttpServletRequest req;
	
	private static class DisconnectData {
		private Map<String, Object> attributes;
		private String authType;
		private Cookie[] cookies;
		private Map<Collection.Key,LinkedList<String>> headers;// this is a Pait List because there could by multiple entries with the same name
		private String method;
		private String pathTranslated;
		private String remoteUser;
		private String requestedSessionId;
		private boolean requestedSessionIdFromCookie;
		//private Request _request;
		private boolean requestedSessionIdFromURL;
		private boolean secure;
		private boolean requestedSessionIdValid;
		private String characterEncoding;
		private int contentLength;
		private String contentType;
		private int serverPort;
		private String serverName;
		private String scheme;
		private String remoteHost;
		private String remoteAddr;
		private String protocol;
		private Locale locale;
	}
	DisconnectData disconnectData;

	/**
	 * Constructor of the class
	 * @param req 
	 * @param max how many is possible to re read
	 */
	public HTTPServletRequestWrap(HttpServletRequest req) {
		this.req=pure(req);
		
		if((servlet_path=attrAsString("javax.servlet.include.servlet_path"))!=null){
			request_uri=attrAsString("javax.servlet.include.request_uri");
			context_path=attrAsString("javax.servlet.include.context_path");
			path_info=attrAsString("javax.servlet.include.path_info");
			query_string = attrAsString("javax.servlet.include.query_string");
		}
		else {
			servlet_path=req.getServletPath();
			request_uri=req.getRequestURI();
			context_path=req.getContextPath();
			path_info=req.getPathInfo();
			query_string = req.getQueryString();
		}
	}
	
	private String attrAsString(String key) {
		Object res = getAttribute(key);
		if(res==null) return null;
		return res.toString();
	}
	
	public static HttpServletRequest pure(HttpServletRequest req) {
		HttpServletRequest req2;
		while(req instanceof HTTPServletRequestWrap){
			req2 =  ((HTTPServletRequestWrap)req).getOriginalRequest();
			if(req2==req) break;
			req=req2;
		}
		return req;
	}

	@Override
	public String getContextPath() {
		return context_path;
	}
	
	@Override
	public String getPathInfo() {
		return path_info;
	}
	
	@Override
	public StringBuffer getRequestURL() {
		return new StringBuffer(isSecure()?"https":"http").
			append("://").
			append(getServerName()).
			append(':').
			append(getServerPort()).
			append(request_uri.startsWith("/")?request_uri:"/"+request_uri);
	}
	
	@Override
	public String getQueryString() {
		return query_string;
	}
	@Override
	public String getRequestURI() {
		return request_uri;
	}
	
	@Override
	public String getServletPath() {
		return servlet_path;
	}
	
	@Override
	public RequestDispatcher getRequestDispatcher(String realpath) {
		return new RequestDispatcherWrap(this,realpath);
	}
	
	public RequestDispatcher getOriginalRequestDispatcher(String realpath) {
		if(disconnected) return null;
		return req.getRequestDispatcher(realpath);
	}

	@Override
	public void removeAttribute(String name) {
		if(disconnected) disconnectData.attributes.remove(name); 
		else req.removeAttribute(name);
	}

	@Override
	public void setAttribute(String name, Object value) {
		if(disconnected) disconnectData.attributes.put(name, value);
		else req.setAttribute(name, value);
	}
	
	/*public void setAttributes(Request request) {
		this._request=request;
	}*/


	@Override
	public Object getAttribute(String name) {
		if(disconnected) return disconnectData.attributes.get(name);
		return req.getAttribute(name);
	}

	public Enumeration getAttributeNames() {
		if(disconnected) {
			return new EnumerationWrapper(disconnectData.attributes);
		}
		return req.getAttributeNames();
		
	}

	@Override
	public ServletInputStream getInputStream() throws IOException {
		//if(ba rr!=null) throw new IllegalStateException();
		if(barr==null) {
			if(!firstRead) {
				PageContext pc = ThreadLocalPageContext.get();
				if(pc!=null) {
					return pc.formScope().getInputStream();
				}
				return new ServletInputStreamDummy(new byte[]{});	//throw new IllegalStateException();
			}
			
			firstRead=false;
			
			if(isToBig(getContentLength())) {
				return req.getInputStream();
			}
			InputStream is=null;
			try {
				barr=IOUtil.toBytes(is=req.getInputStream());
				
				//Resource res = ResourcesImpl.getFileResourceProvider().getResource("/Users/mic/Temp/multipart.txt");
				//IOUtil.copy(new ByteArrayInputStream(barr), res, true);
				
			}
			catch(Throwable t) {
				barr=null;
				return new ServletInputStreamDummy(new byte[]{});	 
			}
			finally {
				IOUtil.closeEL(is);
			}
		}
		
		return new ServletInputStreamDummy(barr);	
	}
	
	@Override
	public Map<String,String[]> getParameterMap() {
		PageContext pc = ThreadLocalPageContext.get();
		FormImpl form=_form(pc);
		URLImpl url=_url(pc);
		
		return ScopeUtil.getParameterMap(
				new URLItem[][]{form.getRaw(),url.getRaw()}, 
				new String[]{form.getEncoding(),url.getEncoding()});
	}

	private static URLImpl _url(PageContext pc) {
		URL u = pc.urlScope();
		if(u instanceof UrlFormImpl) {
			return ((UrlFormImpl) u).getURL();
		}
		return (URLImpl) u;
	}

	private static FormImpl _form(PageContext pc) {
		Form f = pc.formScope();
		if(f instanceof UrlFormImpl) {
			return ((UrlFormImpl) f).getForm();
		}
		return (FormImpl) f;
	}

	@Override
	public Enumeration<String> getParameterNames() {
		return new ItasEnum<String>(getParameterMap().keySet().iterator());
	}

	@Override
	public String[] getParameterValues(String name) {
		return getParameterValues(ThreadLocalPageContext.get(), name); 
	}
	
	public static String[] getParameterValues(PageContext pc, String name) {
		pc = ThreadLocalPageContext.get(pc);
		FormImpl form = _form(pc);
		URLImpl url= _url(pc);
		
		return ScopeUtil.getParameterValues(
				new URLItem[][]{form.getRaw(),url.getRaw()}, 
				new String[]{form.getEncoding(),url.getEncoding()},name);
	}

	private boolean isToBig(int contentLength) {
		if(contentLength<MIN_STORAGE_SIZE) return false;
		if(contentLength>MAX_STORAGE_SIZE) return true;
		Runtime rt = Runtime.getRuntime();
		long av = rt.maxMemory()-rt.totalMemory()+rt.freeMemory();
		return (av-SPACIG)<contentLength;
	}

	/* *
	 * with this method it is possibiliy to rewrite the input as many you want
	 * @return input stream from request
	 * @throws IOException
	 * /
	public ServletInputStream getStoredInputStream() throws IOException {
		if(firstRead || barr!=null) return getInputStream();
		return new ServletInputStreamDummy(new byte[]{});	 
	}*/

	@Override
	public BufferedReader getReader() throws IOException {
		String enc = getCharacterEncoding();
		if(StringUtil.isEmpty(enc))enc="iso-8859-1";
		return IOUtil.toBufferedReader(IOUtil.getReader(getInputStream(), enc));
	}
	
	public void clear() {
		barr=null;
	}

	


	public HttpServletRequest getOriginalRequest() {
		return req;
	}

	public void disconnect() {
		if(disconnected) return;
		disconnectData=new DisconnectData();
		
		// attributes
		{
			Enumeration<String> attrNames = req.getAttributeNames();
			disconnectData.attributes=new HashMap<String, Object>();
			String k;
			while(attrNames.hasMoreElements()){
				k=attrNames.nextElement();
				disconnectData.attributes.put(k, req.getAttribute(k));
			}
		}
		
		// headers
		{
			Enumeration headerNames = req.getHeaderNames();
			disconnectData.headers=new HashMap<Collection.Key, LinkedList<String>>();
			
			String k;
			Enumeration e;
			while(headerNames.hasMoreElements()){
				k=headerNames.nextElement().toString();
				e = req.getHeaders(k);
				LinkedList<String> list=new LinkedList<String>();
				while(e.hasMoreElements()){
					list.add(e.nextElement().toString());
				}
				disconnectData.headers.put(KeyImpl.init(k),list);
			}
		}
		
		// cookies
		{
			Cookie[] _cookies = req.getCookies();
			if(!ArrayUtil.isEmpty(_cookies)) {
				disconnectData.cookies=new Cookie[_cookies.length];
				for(int i=0;i<_cookies.length;i++) 
					disconnectData.cookies[i]=_cookies[i];
			}
			else
				disconnectData.cookies=new Cookie[0];
		}
		
		disconnectData.authType = req.getAuthType();
		disconnectData.method=req.getMethod();
		disconnectData.pathTranslated=req.getPathTranslated();
		disconnectData.remoteUser=req.getRemoteUser();
		disconnectData.requestedSessionId=req.getRequestedSessionId();
		disconnectData.requestedSessionIdFromCookie=req.isRequestedSessionIdFromCookie();
		disconnectData.requestedSessionIdFromURL=req.isRequestedSessionIdFromURL();
		disconnectData.secure = req.isSecure();
		disconnectData.requestedSessionIdValid=req.isRequestedSessionIdValid();
		disconnectData.characterEncoding = req.getCharacterEncoding();
		disconnectData.contentLength = req.getContentLength();
		disconnectData.contentType=req.getContentType();
		disconnectData.serverPort=req.getServerPort();
		disconnectData.serverName=req.getServerName();
		disconnectData.scheme=req.getScheme();
		disconnectData.remoteHost=req.getRemoteHost();
		disconnectData.remoteAddr=req.getRemoteAddr();
		disconnectData.protocol=req.getProtocol();
		disconnectData.locale=req.getLocale();
		if(barr==null) {
			try {
				barr=IOUtil.toBytes(req.getInputStream(),true);
			}
			catch (IOException e) {
				// e.printStackTrace();
			}
		}
		disconnected=true;
		req=null;
	}
	
	static class ArrayEnum<E> implements Enumeration<E> {

		@Override
		public boolean hasMoreElements() {
			return false;
		}

		@Override
		public E nextElement() {
			return null;
		}
		
	}
	
	static class ItasEnum<E> implements Enumeration<E> {

		private Iterator<E> it;

		public ItasEnum(Iterator<E> it){
			this.it=it;
		}
		@Override
		public boolean hasMoreElements() {
			return it.hasNext();
		}

		@Override
		public E nextElement() {
			return it.next();
		}
	}
	
	static class EmptyEnum<E> implements Enumeration<E> {

		@Override
		public boolean hasMoreElements() {
			return false;
		}

		@Override
		public E nextElement() {
			return null;
		}
	}
	
	static class StringItasEnum implements Enumeration<String> {

		private Iterator<?> it;

		public StringItasEnum(Iterator<?> it){
			this.it=it;
		}
		@Override
		public boolean hasMoreElements() {
			return it.hasNext();
		}

		@Override
		public String nextElement() {
			return it.next().toString();
		}
		
	}

	@Override
	public String getAuthType() {
		if(disconnected) return disconnectData.authType;
		return req.getAuthType();
	}

	@Override
	public Cookie[] getCookies() {
		if(disconnected) return disconnectData.cookies;
		return req.getCookies();
		
	}

	@Override
	public long getDateHeader(String name) {
		if(!disconnected) return req.getDateHeader(name);
		
		String h = getHeader(name);
		if(h==null) return -1;
		DateTime dt = DateCaster.toDateAdvanced(h, null,null);
		if(dt==null) throw new IllegalArgumentException("cannot convert ["+getHeader(name)+"] to date time value");
		return dt.getTime();
	}

	@Override
	public int getIntHeader(String name) {
		if(!disconnected) return req.getIntHeader(name);
		
		String h = getHeader(name);
		if(h==null) return -1;
		Integer i = Caster.toInteger(h, null);
		if(i==null) throw new NumberFormatException("cannot convert ["+getHeader(name)+"] to int value");
		return i.intValue();
	}

	@Override
	public String getHeader(String name) {
		if(!disconnected) return req.getHeader(name);
		
		LinkedList<String> value = disconnectData.headers.get(KeyImpl.init(name));
		if(value==null) return null;
		return value.getFirst();
	}

	@Override
	public Enumeration getHeaderNames() {
		if(!disconnected) return req.getHeaderNames();
		return new StringItasEnum(disconnectData.headers.keySet().iterator());
	}

	@Override
	public Enumeration getHeaders(String name) {
		if(!disconnected) return req.getHeaders(name);
		
		LinkedList<String> value = disconnectData.headers.get(KeyImpl.init(name));
		if(value!=null)return new ItasEnum<String>(value.iterator());
		return new EmptyEnum<String>();
	}

	@Override
	public String getMethod() {
		if(!disconnected) return req.getMethod();
		return disconnectData.method;
	}

	@Override
	public String getPathTranslated() {
		if(!disconnected) return req.getPathTranslated();
		return disconnectData.pathTranslated;
	}

	@Override
	public String getRemoteUser() {
		if(!disconnected) return req.getRemoteUser();
		return disconnectData.remoteUser;
	}

	@Override
	public String getRequestedSessionId() {
		if(!disconnected) return req.getRequestedSessionId();
		return disconnectData.requestedSessionId;
	}

	@Override
	public HttpSession getSession() {
		if(!disconnected) return req.getSession();
		throw new RuntimeException("this method is not supported when root request is gone");
	}

	@Override
	public HttpSession getSession(boolean create) {
		if(!disconnected) return req.getSession(create);
		throw new RuntimeException("this method is not supported when root request is gone");
	}

	@Override
	public Principal getUserPrincipal() {
		if(!disconnected) return req.getUserPrincipal();
		throw new RuntimeException("this method is not supported when root request is gone");
	}

	@Override
	public boolean isRequestedSessionIdFromCookie() {
		if(!disconnected) return req.isRequestedSessionIdFromCookie();
		return disconnectData.requestedSessionIdFromCookie;
	}

	@Override
	public boolean isRequestedSessionIdFromURL() {
		if(!disconnected) return req.isRequestedSessionIdFromURL();
		return disconnectData.requestedSessionIdFromURL;
	}

	@Override
	public boolean isRequestedSessionIdFromUrl() {
		return isRequestedSessionIdFromURL();
	}

	@Override
	public boolean isRequestedSessionIdValid() {
		if(!disconnected) return req.isRequestedSessionIdValid();
		return disconnectData.requestedSessionIdValid;
	}

	@Override
	public boolean isUserInRole(String role) {
		if(!disconnected) return req.isUserInRole(role);
		throw new RuntimeException("this method is not supported when root request is gone");
	}

	@Override
	public String getCharacterEncoding() {
		if(!disconnected) return req.getCharacterEncoding();
		return disconnectData.characterEncoding;
	}

	@Override
	public int getContentLength() {
		if(!disconnected) return req.getContentLength();
		return disconnectData.contentLength;
	}

	@Override
	public String getContentType() {
		if(!disconnected) return req.getContentType();
		return disconnectData.contentType;
	}

	@Override
	public Locale getLocale() {
		if(!disconnected) return req.getLocale();
		return disconnectData.locale;
	}

	@Override
	public Enumeration getLocales() {
		if(!disconnected) return req.getLocales();
		throw new RuntimeException("this method is not supported when root request is gone");
	}

	@Override
	public String getParameter(String name) {
		if(!disconnected) return req.getParameter(name);
		String[] values = getParameterValues(name);
		if(ArrayUtil.isEmpty(values)) return null;
		return values[0];
	}

	@Override
	public String getProtocol() {
		if(!disconnected) return req.getProtocol();
		return disconnectData.protocol;
	}

	@Override
	public String getRealPath(String path) {
		if(!disconnected) return req.getRealPath(path);
		throw new RuntimeException("this method is not supported when root request is gone");
	}

	@Override
	public String getRemoteAddr() {
		if(!disconnected) return req.getRemoteAddr();
		return disconnectData.remoteAddr;
	}

	@Override
	public String getRemoteHost() {
		if(!disconnected) return req.getRemoteHost();
		return disconnectData.remoteHost;
	}

	@Override
	public String getScheme() {
		if(!disconnected) return req.getScheme();
		return disconnectData.scheme;
	}

	@Override
	public String getServerName() {
		if(!disconnected) return req.getServerName();
		return disconnectData.serverName;
	}

	@Override
	public int getServerPort() {
		if(!disconnected) return req.getServerPort();
		return disconnectData.serverPort;
	}

	@Override
	public boolean isSecure() {
		if(!disconnected) return req.isSecure();
		return disconnectData.secure;
	}

	@Override
	public void setCharacterEncoding(String enc) throws UnsupportedEncodingException {
		if(!disconnected) req.setCharacterEncoding(enc);
		else disconnectData.characterEncoding=enc;
	}
}