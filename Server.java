

/**
 * Simple HTTP服务器 by You
 */

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import Decoder.BASE64Decoder;

class Server implements HttpConstants {
	/* static class data methods */
	/* print to stdout */
	protected static void p(String s) {
		System.out.println(s);
	}
	/* print to the log file */
	protected static void log(String s) {
		synchronized (log) {
			log.println(s);
			log.flush();
		}
	}

	/* Default std stream */
	static PrintStream log = null;
	/*
	 * our server's configuration information is stored in these properties
	 */
	protected static Properties props = new Properties();

	/* the web server's virtual root */
	static File root;

	/* timeout on client connections */
	static int timeout = 0;

	/* max # handlers threads */
	static int handlers = 100;

	/* Java thread pool */
	private final static ExecutorService pool = Executors
			.newFixedThreadPool(handlers);

	/* load properties from user.dir */
	static void loadProps() throws IOException {
		root = new File(System.getProperty("user.dir"));//System.getProperty("user.dir")获得用户当前工作目录
		if (timeout <= 1000) {
			timeout = 60000;
		}
		if (log == null) {
			p("logging to stdout");
			log = System.out;
		}
	}

	static void printProps() {
		p("root=" + root);
		p("timeout=" + timeout);
		p("handlers=" + handlers);
	}

	public static void main(String[] a) throws Exception {
		int port = 9090;
		if (a.length > 0) {
			port = Integer.parseInt(a[0]);
		}
		loadProps();
		printProps();
		TimeZone.setDefault(TimeZone.getTimeZone("GMT"));

		ServerSocket ss = new ServerSocket(port);
		while (true) {
			Socket s = ss.accept();
			HandleClientReq w = new HandleClientReq(s);
			pool.execute(w);//多线程
			System.out.println("7777##");
		}
	}
}

class HandleClientReq extends Server implements HttpConstants, Runnable {
	final static int BUF_SIZE = 2048;
	static final byte[] EOL = { (byte) '\r', (byte) '\n' };
	InputStream is = null;
	PrintStream ps = null;
	InputStream targetIs = null;
	PrintStream targetPs = null;
	/* Some useful info of the request message */
	boolean isKeepAlive = true;
	boolean toBeProxyServer = true;
	String Method = null;
	String Url = null;
	String Ver = null;
	String resCode = null;
	String reqBody = "";
	String author = null;
	/* If the header contains keep-alive then it is the times client requests */
	int count = 0;
	/* buffer to use for requests */
	byte[] buf;
	/* Socket to client we're handling */
	private Socket s;
	private Socket targetSocket = null;
	/* The request headers from client */
	Map<String, String> reqHeader = new HashMap<>();
	/* The headers response to client */
	Map<String, String> resHeader = new HashMap<>();

	HandleClientReq(Socket s) {
		buf = new byte[BUF_SIZE];
		this.s = s;
	}

	public synchronized void run() {
		try {
			handleClient();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	void handleClient() throws IOException {
		is = new BufferedInputStream(s.getInputStream());
		ps = new PrintStream(s.getOutputStream());
		s.setSoTimeout(Server.timeout);
		s.setTcpNoDelay(true);
		try {
			while (isKeepAlive) {
				/* zero out the buffer from last time */
				for (int i = 0; i < BUF_SIZE; i++) {
					buf[i] = 0;
				}
				reqHeader.clear();
				resHeader.clear();
				parseMessages(0, is);//解析请求头

				if (reqHeader.get("Connection") != null) {
					if (reqHeader.get("Connection").equals("close"))
						isKeepAlive = false;
				} else if (reqHeader.get("Proxy-Connection") != null) {
					toBeProxyServer = true;
					if (reqHeader.get("Proxy-Connection").equals("close"))
						isKeepAlive = false;
				} else {
					if (Ver.equals("HTTP/1.0")) {
						isKeepAlive = false;
					}
				}//设置一些属性判断是不是长连接模式或代理模式
				author = reqHeader.get("Authorization");
				if (toBeProxyServer)
					author = reqHeader.get("Proxy-Authorization");
				String tmp = authorize();
				if (!tmp.equals("Success")) {
					resHeader.put("Server", "YOU's Server");
					resHeader.put("Date", getFormatTime(new Date().toString()));
					resHeader.put("Content-Length", "0");
					if (toBeProxyServer) {
						resHeader.put("Proxy-Authenticate", "Basic realm=\""
								+ tmp + "\"");
						resCode = "407";
					} else {
						resHeader.put("WWW-Authenticate", "Basic realm=\""
								+ tmp + "\"");
						resCode = "401";
					}
					sendResMsg();
					continue;
				}
				// Read the body content as well if it exists
				if (reqHeader.get("Content-Length") != null) {
					byte[] bodyBuf = new byte[Integer.parseInt(reqHeader
							.get("Content-Length"))];
					if (is.read(bodyBuf, 0, bodyBuf.length) == bodyBuf.length)
						reqBody = new String(bodyBuf);
				} /*
				 * else if (reqHeader.containsKey("Transfer-Encoding")) { if
				 * (reqHeader.get("Transfer-Encoding").equals("chunked")) {
				 * List<Byte[]> chunkedBody = new ArrayList<>(); } }
				 */
				if (toBeProxyServer)
					transmitMsg();
				else {
					if (reqHeader.get("Cookie") == null)
						resHeader.put("Set-Cookie",
								"youserverid=summity7; Expires ="
										+ cookieTime());
					else
						handleCookie();
					if (Method.equals("GET") || Method.equals("HEAD"))
						getMethod();
					else if (Method.equals("POST"))
						postMethod();
					else {
						ps.print("HTTP/1.1 " + HTTP_BAD_METHOD
								+ " unsupported method type: ");
						ps.print(Method);
						ps.write(EOL);
						ps.flush();
					}
				}
				count++;
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			log(String
					.format("Requests finished.The connection from:%s is closed.Keep-Alive:%s.RequestCounts:%d",
							s.getInetAddress().getHostAddress(),
							String.valueOf(isKeepAlive), count));
			if (targetSocket != null) {
				targetSocket.close();
			}
			s.close();
		}
	}

	void handleCookie() {
		String tmp = reqHeader.get("Cookie");
		if (tmp.indexOf("youserverid") != -1) {
			if (tmp.indexOf(";") != -1) {
				String[] cookies = tmp.split(";");
				for (String cookie : cookies) {
					String[] info = cookie.split("=");
					if (info[0].equals("youserverid"))
						if (info[1].equals("summity7"))
							log("Cookie status:right.");
				}
			} else {
				String[] info = tmp.split("=");
				if (info[0].equals("youserverid"))
					if (info[1].equals("summity7"))
						log("Cookie status:right.");
			}
		} else
			resHeader.put("Set-Cookie", "youserverid=summity7; Expires ="
					+ cookieTime());
	}

	String cookieTime() {
		Date date = new Date();
		Calendar calendar = new GregorianCalendar();
		calendar.setTime(date);
		calendar.add(calendar.DATE, +1);
		date = calendar.getTime();
		String tmp1 = date.toString();
		String[] tmp2 = tmp1.split(" ");
		String time = tmp2[0] + ", " + tmp2[2] + " " + tmp2[1] + " " + tmp2[5]
				+ " " + tmp2[3] + " " + tmp2[4];
		return time;
	}

	String getFormatTime(String time) {//重定义
		
		String[] tmp2 = time.split(" ");
		String tmp = tmp2[0] + ", " + tmp2[2] + " " + tmp2[1] + " " + tmp2[5]
				+ " " + tmp2[3] + " " + tmp2[4];

		return tmp;
	}
/*
 * *log in
 * 
 */
	String authorize() throws IOException {
		System.out.println(author);
		// Only handle with the Basic auth type
		if (author == null || author.equals(""))
			return "Login to YOU's Server";
		else {
			log("Author before decode:" + author);
			String[] tmp = author.split(" ");
			BASE64Decoder decoder = new BASE64Decoder();
			byte[] b = decoder.decodeBuffer(tmp[1]);
			// return new String(b);
			author = new String(b);
			if (author.equals(":"))
				return "Login to YOU's Server";
			String[] info = author.split(":");
			log("Author after decode:" + author);
			if (info.length == 1)
				return "Wrong password";
			if (toBeProxyServer) {
				if (authorsProxy.get(info[0]) != null) {
					if (authorsProxy.get(info[0]).equals(info[1]))
						return "Success";
					else
						return "Wrong password";
				} else
					return "Wrong username";
			} else {
				if (authors.get(info[0]) != null) {
					if (authors.get(info[0]).equals(info[1]))
						return "Success";
					else
						return "Wrong password";
				} else
					return "Wrong username";
			}
		}
	}

	void transmitMsg() throws NumberFormatException, UnknownHostException,
			IOException {
		String XForward;
		String targetHost, transMethod;
		String transUrl = "/";
		String targetPort = "80";
		String tmp1 = Url.replace("http://", "");
		tmp1 = tmp1.replace("https://", "");
		int i = tmp1.indexOf("/");
		if (i != -1)
			transUrl = tmp1.substring(i);
		if (reqHeader.get("Host") != null)
			targetHost = reqHeader.get("Host");
		else {
			if (i != -1)
				targetHost = tmp1.substring(0, i);
			else
				targetHost = tmp1;
		}
		i = targetHost.indexOf(":");
		if (i != -1) {
			String[] tmp2 = targetHost.split(":");
			targetHost = tmp2[0];
			targetPort = tmp2[1];
		}
		targetSocket = new Socket(targetHost, Integer.parseInt(targetPort));
		log("Connect to the target host:" + targetHost + " port:" + targetPort);
		targetIs = targetSocket.getInputStream();
		targetPs = new PrintStream(targetSocket.getOutputStream());

		transMethod = Method;
		if (Method.equals("CONNECT"))
			transMethod = "GET";
		reqHeader.remove("Proxy-Connection");
		if (isKeepAlive)
			reqHeader.put("Connection", "keep-alive");
		else
			reqHeader.put("Connection", "close");
		reqHeader.remove("Proxy-Authorization");
		if (reqHeader.get("X-Forwarded-For") == null)
			XForward = s.getInetAddress().getHostAddress() + ", "
					+ InetAddress.getLocalHost().getHostAddress();
		else
			XForward = reqHeader.get("X-Forwarded-For") + ", "
					+ InetAddress.getLocalHost().getHostAddress();
		reqHeader.put("X-Forwarded-For", XForward);
		log("Via: " + XForward);
		sendReqMsg(targetPs, transUrl, transMethod);
		for (int j = 0; j < BUF_SIZE; j++) {
			buf[j] = 0;
		}
		int n = parseMessages(1, targetIs);
		log("Transmit resmsg:" + new String(buf, 0, n));
		// ps.write(buf, 0, n);
		sendResMsg();
		if (resHeader.get("Content-Length") != null) {
			byte[] bodyBuf = new byte[Integer.parseInt(resHeader
					.get("Content-Length"))];
			if (targetIs.read(bodyBuf) == bodyBuf.length) {
				ps.write(bodyBuf, 0, bodyBuf.length);
				ps.flush();
				log("Data send successfully.Length:" + bodyBuf.length);
				log("Data from server:" + new String(bodyBuf));
			}
		}
		if (resHeader.get("Transfer-Encoding") != null) {
			String tmpLen = "";
			i = -1;
			byte[] bodyBuf = null;
			log("Transmit data...");
			while (true) {
				i++;
				buf[i] = (byte) targetIs.read();
				if (buf[i] != -1) {
					if (buf[i] == '\r') {
						// System.out.println("###break");
						i++;
						buf[i] = (byte) targetIs.read();
						if(buf[i] == '\n')
							tmpLen = new String(buf,0,i-1);
						tmpLen = String.valueOf(new BigInteger(tmpLen, 16).toString());
						log("Chunck length: "+tmpLen);
						ps.write(buf, 0, i+1);
						if(tmpLen.equals("0")){
							i = 0;
							boolean isEnd = true;
							while (true) {
								buf[i] = (byte) targetIs.read();
								if (buf[i] != -1) {
									if (buf[i] == '\r') {
										if (isEnd) {
											i++;
											buf[i] = (byte) targetIs.read();
											break;
										}
									} else if (buf[i] == '\n')
										isEnd = true;
									else
										isEnd = false;
								} else {
									continue;
								}
								i++;
							}
							ps.write(buf,0,i+1);
							break;
						}
						bodyBuf = new byte[Integer.parseInt(tmpLen)+2];
						if (targetIs.read(bodyBuf) == bodyBuf.length) {
							ps.write(bodyBuf, 0, bodyBuf.length);
							ps.flush();
							log("One chuncked send successfully.Length:" + bodyBuf.length);
							log("This chuncked data from server:" + new String(bodyBuf));
						}
						i = -1;
					}
				} else {
					continue;
				}
			}
		}
	}

	int parseMessages(int reqOrRes, InputStream is) throws IOException {
		InputStream isTmp = is;
		int i = 0;
		boolean isEnd = false;
		log("Parsing msg...");
		while (true) {
			buf[i] = (byte) isTmp.read();
			if (buf[i] != -1) {
				if (buf[i] == '\r') {
					if (isEnd) {
						// System.out.println("###break");
						i++;
						buf[i] = (byte) isTmp.read();
						break;
					}
				} else if (buf[i] == '\n')
					isEnd = true;
				else
					isEnd = false;
			} else {
				continue;
				// throw new IOException("Read closed.");
			}
			i++;
		}
		String msg = new String(buf, 0, i);
		log("Parse result:\n" + msg);
		// System.out.println(i+"##");
		// System.out.println(buf[655]+"##"+buf[656]);
		//将get头和下面的内容分开
		String[] lines = msg.split("\r\n");
		String[] oneLine = lines[0].split(" ");
		if (reqOrRes == 0) {
			Method = oneLine[0];
			Url = oneLine[1];
			Ver = oneLine[2];
		} else if (reqOrRes == 1) {
			Ver = oneLine[0];
			resCode = oneLine[1];
		}
		if (reqOrRes == 0) {
			for (int j = 1; j < lines.length - 1; j++) {
				oneLine = lines[j].split(": ");
				// System.out.println(oneLine[0]+"##"+oneLine[1]);
				reqHeader.put(oneLine[0], oneLine[1]);
			}
		} else if (reqOrRes == 1) {
			for (int j = 1; j < lines.length - 1; j++) {
				oneLine = lines[j].split(": ");
				// System.out.println(oneLine[0]+"##"+oneLine[1]);
				resHeader.put(oneLine[0], oneLine[1]);
			}
		}
		return i;
	}

	void getMethod() throws IOException {
		String ct = null;
		String cl = null;
		String lm = null;
		File targ = new File(Server.root, Url);
		if (targ.isDirectory()) {
			File ind = new File(targ, "index.html");
			if (ind.exists()) {
				targ = ind;
			}
		}
		if (!targ.exists())
			resCode = String.valueOf(HTTP_NOT_FOUND);
		else {
			resCode = String.valueOf(HTTP_OK);
			if (!targ.isDirectory()) {
				cl = String.valueOf(targ.length());
				lm = String.valueOf(getFormatTime(new Date(targ.lastModified())
						.toString()));
				String name = targ.getName();
				int ind = name.lastIndexOf('.');
				if (ind > 0)
					ct = (String) map.get(name.substring(ind));
				if (ct == null)
					ct = "unknown/unknown";
			} else {
				ct = "text/html";
			}
		}
		resHeader.put("Server", "YOU's Server");
		resHeader.put("Date", getFormatTime(new Date().toString()));
		if (isKeepAlive)
			resHeader.put("Connection", "keep-alive");
		else
			resHeader.put("Connection", "close");
		if (ct != null) {
			resHeader.put("Content-Type", ct);
			if (cl != null && lm != null) {
				resHeader.put("Content-Length", cl);
				resHeader.put("Last-Modified", lm);
				if (reqHeader.get("If-Modified-Since") != null) {
					String lmReq = reqHeader.get("If-Modified-Since");
					if (lm.equals(lmReq)) {
						resCode = "304";
						resHeader.remove("Content-Length");
						sendResMsg();
						return;
					}
				}
			} else
				resHeader.put("Transfer-Encoding", "chunked");
		} else {
			resHeader.put("Content-Type", "");
			resHeader.put("Content-Length", String.valueOf(new String(
					"Not Found\n\nThe requested resource was not found.\n")
					.length()));
		}
		log("From " + s.getInetAddress().getHostAddress() + ": GET "
				+ targ.getAbsolutePath() + "-->" + resCode);
		sendResMsg();
		if (Method.equals("GET") || Method.equals("POST")) {
			if (resCode.equals(String.valueOf(HTTP_OK)))
				if (Method.equals("POST"))
					sendFileByChuncked(targ);
				else
					sendFile(targ);
			else
				send404();
		}
	}

	void postMethod() throws IOException {
		int i = Url.indexOf("?");
		if (i != -1)
			Url = Url.substring(0, i);
		if (!reqBody.equals("")) {
			log("Write post msg into Postdata.txt..\nThe msg:" + reqBody);
			File postData = new File(Server.root, "Postdata.txt");
			FileWriter writer = new FileWriter(postData, true);
			writer.write(reqBody + "\r\n");// 在已有的基础上添加字符串
			writer.flush();
			writer.close();
		}
		resHeader.put("Transfer-Encoding", "chunked");
		getMethod();
	}

	void sendResMsg() {
		StringBuilder resMsg = new StringBuilder();
		String resText = (String) resTxt.get(resCode);
		// System.out.println(resCode + "##" + resText);
		log(String.format("##Status:\nHTTP/1.1 %s %s\n", resCode, resText));
		ps.print(String.format("HTTP/1.1 %s %s\r\n", resCode, resText));
		Iterator iter = resHeader.entrySet().iterator();//遍历resH存入resHeader树，写进resM
		while (iter.hasNext()) {
			Map.Entry entry = (Map.Entry) iter.next();
			resMsg.append(String.format("%s: %s\r\n", entry.getKey(),
					entry.getValue()));
		}
		resMsg.append("\r\n");
		log("##Reponse headers:\n" + resMsg.toString());
		ps.print(resMsg.toString());
		ps.flush();
	}

	void sendReqMsg(PrintStream targetPs, String transUrl, String transMethod) {
		StringBuilder reqMsg = new StringBuilder();
		targetPs.print(String
				.format("%s %s %s\r\n", transMethod, transUrl, Ver));
		Iterator iter = reqHeader.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry entry = (Map.Entry) iter.next();
			reqMsg.append(String.format("%s: %s\r\n", entry.getKey(),
					entry.getValue()));
		}
		reqMsg.append("\r\n");
		reqMsg.append(reqBody);
		log("##Request to target server headers:\n" + reqMsg.toString());
		targetPs.print(reqMsg.toString());
		targetPs.flush();
	}

	void send404() throws IOException {
		ps.print("Not Found\n\nThe requested resource was not found.\n");
	}

	void sendFile(File targ) throws IOException {
		InputStream isFile = null;
		if (targ.isDirectory()) {
			listDirectory(targ, ps);
			return;
		} else {
			isFile = new FileInputStream(targ.getAbsolutePath());
		}

		try {
			int n;
			while ((n = isFile.read(buf)) > 0) {
				ps.write(buf, 0, n);
			}
		} finally {
			isFile.close();
		}
	}

	void sendFileByChuncked(File targ) throws IOException {
		InputStream isFile = null;
		if (targ.isDirectory()) {
			listDirectory(targ, ps);
			return;
		} else {
			isFile = new FileInputStream(targ.getAbsolutePath());
		}
		try {
			int n;
			while ((n = isFile.read(buf)) > 0) {
				ps.print(Integer.toHexString(n));
				ps.write(EOL);
				ps.write(buf, 0, n);
				ps.write(EOL);
			}
			ps.print("0");
			ps.write(EOL);
			ps.write(EOL);
		} finally {
			isFile.close();
		}
	}

	/* mapping of file extensions to content-types */
	static java.util.Hashtable map = new java.util.Hashtable();
	static java.util.Hashtable resTxt = new java.util.Hashtable();
	static java.util.Hashtable authors = new java.util.Hashtable();
	static java.util.Hashtable authorsProxy = new java.util.Hashtable();
	static java.util.Hashtable cookies = new java.util.Hashtable();
	static {
		fillMap();
	}

	static void setSuffix(String k, String v) {
		map.put(k, v);
	}

	static void setRestxt(String k, String v) {
		resTxt.put(k, v);
	}

	static void setAuthors(String k, String v) {
		authors.put(k, v);
	}

	static void setProxyAuthors(String k, String v) {
		authorsProxy.put(k, v);
	}

	static void setCookies(String k, String v) {
		cookies.put(k, v);
	}

	static void fillMap() {
		setSuffix("", "content/unknown");
		setSuffix(".uu", "application/octet-stream");
		setSuffix(".exe", "application/octet-stream");
		setSuffix(".ps", "application/postscript");
		setSuffix(".zip", "application/zip");
		setSuffix(".sh", "application/x-shar");
		setSuffix(".tar", "application/x-tar");
		setSuffix(".snd", "audio/basic");
		setSuffix(".au", "audio/basic");
		setSuffix(".wav", "audio/x-wav");
		setSuffix(".gif", "image/gif");
		setSuffix(".jpg", "image/jpeg");
		setSuffix(".jpeg", "image/jpeg");
		setSuffix(".htm", "text/html");
		setSuffix(".html", "text/html");
		setSuffix(".text", "text/plain");
		setSuffix(".c", "text/plain");
		setSuffix(".cc", "text/plain");
		setSuffix(".c++", "text/plain");
		setSuffix(".h", "text/plain");
		setSuffix(".pl", "text/plain");
		setSuffix(".txt", "text/plain");
		setSuffix(".java", "text/plain");
		setRestxt("200", "OK");
		setRestxt("304", "not modified");
		setRestxt("404", "not found");
		setRestxt("405", "unsupported method type: ");
		setRestxt("401", "Unauthorized");
		setRestxt("407", "Proxy Authentication Required");
		setAuthors("You", "y123456");
		setProxyAuthors("You", "Y123456");
		setCookies("youserverid", "summity7");
	}

	void listDirectory(File dir, PrintStream ps) throws IOException {
		String tmp = null;
		int len;
		String absolutePath = "";
		String realPath = null;
		String[] tmpArray;
		tmp = "<html>\n<head>\n<title>Directory listing</title><P>\n</head>\n<body>\n<span>Directory listing</span><P>\n";
		len = tmp.length();
		ps.print(Integer.toHexString(len));
		ps.write(EOL);
		ps.print(tmp);
		ps.write(EOL);
		tmp = "<A HREF=\"..\\\">Parent Directory</A><BR>\n";
		len = tmp.length();
		ps.print(Integer.toHexString(len));
		ps.write(EOL);
		ps.print(tmp);
		ps.write(EOL);
		String[] list = dir.list();
		for (int i = 0; list != null && i < list.length; i++) {
			File f = new File(dir, list[i]);
			/*
			 * realPath = dir.getAbsolutePath(); log(realPath); tmpArray =
			 * realPath.split("\\\\"); for(int j = 3;j<tmpArray.length;j++)
			 * absolutePath = absolutePath+"/"+tmpArray[j]; absolutePath =
			 * absolutePath.substring(1);
			 */
			if (f.isDirectory()) {
				tmp = "<A HREF=\"" + list[i] + "/\">" + list[i] + "/</A><BR>\n";
				len = tmp.length();
				ps.print(Integer.toHexString(len));
				ps.write(EOL);
				ps.print(tmp);
				ps.write(EOL);
			} else {
				tmp = "<A HREF=\"" + list[i] + "\">" + list[i] + "</A><BR>\n";
				len = tmp.length();
				ps.print(Integer.toHexString(len));
				ps.write(EOL);
				ps.println(tmp);
				ps.write(EOL);
			}
		}
		tmp = "<P><HR><BR><I>" + (getFormatTime(new Date().toString()))
				+ "</I>\n</body>\n</html>";
		len = tmp.length();
		ps.print(Integer.toHexString(len));
		ps.write(EOL);
		ps.println(tmp);
		ps.write(EOL);
		ps.print("0");
		ps.write(EOL);
		ps.write(EOL);
	}

}
