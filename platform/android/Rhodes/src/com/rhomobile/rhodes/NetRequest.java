/*------------------------------------------------------------------------
* (The MIT License)
* 
* Copyright (c) 2008-2011 Rhomobile, Inc.
* 
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
* 
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
* 
* http://rhomobile.com
*------------------------------------------------------------------------*/

package com.rhomobile.rhodes;

import android.content.res.AssetFileDescriptor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpCookie;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Base64;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

import com.rhomobile.rhodes.file.RhoFileApi;
import com.rhomobile.rhodes.socket.SSLImpl;

import org.apache.http.protocol.HTTP;

public class NetRequest
{

    private static final char[] HEXADECIMAL = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd',
            'e', 'f'
    };

    private static final String COOKIES_HEADER = "Set-Cookie";

    private class JCMultipartItem
    {
        public String m_strFilePath;
        public String m_strBody;
        public String m_strName, m_strFileName, m_strContentType;
        public String m_strDataPrefix;
        public JCMultipartItem() {}
    }

    private class AuthSettings {
        private static final int QOP_MISSING = 0;
        private static final int QOP_AUTH_INT = 1;
        private static final int QOP_AUTH = 2;

        private static final int HTTP_NONE = 0;
        private static final int HTTP_BASIC = 1;
        private static final int HTTP_DIGEST = 2;

        public int authType = HTTP_NONE;
        public int qopVariant = QOP_MISSING;
        public String method = null;
        public String nonce = null;
        public String opaque = null;
        public String algo = null;
        public String qop = null;
        public String realm = null;
        public String cnonce = null;
        public String charset = null;
        public String uri = null;
        public String nc = "00000001";
        public String serverResponse = null;

        public String user = null;
        public String pwd = null;
        public String authHeader = null;

        public AuthSettings() {}
    }

    private static final String TAG = "JNetRequest";

    private HttpURLConnection connection = null;
    private String url = null;
    //private String body = null;
    private byte[] body = null;
    private String method = null;
    private int fd = -1;
    private boolean sslVerify = true;
    private long timeout = 30;
    private byte[] responseBody = null;
    HashMap<String, String> headers = null;
    Map<String, List<String>> response_headers = null;
    List<JCMultipartItem> multipartItems = new ArrayList<JCMultipartItem>();

    AuthSettings auth_storage = null;
    private long opaque_object = 0;


    public void SetAuthSettings(String u, String p, int type) {
        auth_storage.user = u;
        auth_storage.pwd = p;
        auth_storage.authType = type;
    }

    public void SetOpaqueObject(long value) {
        opaque_object = value;
    }

    public NetRequest() {
        auth_storage = new AuthSettings();
    }

    private native void CallbackData(long opaque, byte[] data, int size);

    public void AddMultiPartData(String strFilePath, String strBody, String strName, String strFileName, String strContentType, String strDataPrefix) {
        JCMultipartItem item = new JCMultipartItem();
        item.m_strFilePath = strFilePath;
        item.m_strBody = strBody;
        item.m_strName = strName;
        item.m_strFileName = strFileName;
        item.m_strContentType = strContentType;
        item.m_strDataPrefix = strDataPrefix;
        multipartItems.add(item);
    }

    public int doPull(String u, String m, byte[] b, int _fd, HashMap<String, String> h, boolean verify, long t) {
        url = u;
        body = b;
        method = m;
        fd = _fd;
        sslVerify = verify;
        timeout = t;
        headers = h;

        try {
            if(method.equals("POST")) {
                int code = postData(false);
                return code;
            }
            else {
               int code = getData(false);
               return code;
            }

        }
        catch (java.io.IOException e) {
            Logger.E( TAG,  e.getClass().getSimpleName() + ": " + e.getMessage() );
        }
        catch (java.lang.Exception e) {
            Logger.E( TAG,  e.getClass().getSimpleName() + ": " + e.getMessage() );
        }

        return 0;
    }

    private void fillHeaders() {
        for(Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            connection.setRequestProperty(key, value);
        }

        if(auth_storage.authHeader != null)
            connection.setRequestProperty("Authorization", auth_storage.authHeader);
    }

    private byte[] readFromStream(InputStream stream) throws java.io.IOException {
        byte[] buffer = new byte[4096];

        ByteArrayOutputStream response = new ByteArrayOutputStream();
        int recv = 0;
        while ((recv = stream.read(buffer)) > 0) {
            CallbackData(opaque_object, buffer, recv);
            if(fd < 0)
                response.write(buffer, 0, recv);
        }
        stream.close();

        return response.toByteArray();
    }

    private long getMultiPartDataSize() {
        long size = 0;
        byte[] m_multipartPostfix = body;
        for (JCMultipartItem item : multipartItems) {
            if(!item.m_strFilePath.isEmpty()) {
                File ofile = new File(item.m_strFilePath);
                if(ofile.exists())
                    size += ofile.length();
                else {
                    AssetFileDescriptor asfd = RhoFileApi.openAssetFd(item.m_strFilePath);
                    size += asfd.getLength();
                }
                size += item.m_strDataPrefix.length();
            }
            else {
                size += item.m_strDataPrefix.length();
                size += item.m_strBody.length();
            }
        }
        size += m_multipartPostfix.length;
        return size;
    }

    private boolean writeMultiPartData(OutputStream stream) throws java.io.IOException {

        byte buffer[] = new byte[4096];
        int recv = 0;
        byte[] m_multipartPostfix = body;

        for (JCMultipartItem item : multipartItems) {
            if(!item.m_strFilePath.isEmpty()) {
                stream.write(item.m_strDataPrefix.getBytes());

                InputStream in = RhoFileApi.open(item.m_strFilePath);
                while((recv = in.read(buffer)) > 0) {
                    stream.write(buffer, 0, recv);
                }

                in.close();
            }
            else {
                stream.write(item.m_strDataPrefix.getBytes());
                stream.write(item.m_strBody.getBytes());
            }
        }
        stream.write(m_multipartPostfix);
        return true;
    }

    private static class RequestThread extends Thread
    {
        protected int code = 0;
        protected byte[] response = null;
        protected Map<String, List<String>> response_headers = null;

        public RequestThread() {}
        public byte[] getResponse() {
            return response;
        }
        public Map<String, List<String>> getResponseHeaders() { return response_headers; }
        public int getResponseCode() {
            return code;
        }
    }

    public String getCookies() {
        if(response_headers == null) return null;
        List<String> cookies = response_headers.get(COOKIES_HEADER);
        if(cookies == null) return null;

        String str_cookies = "";

        for(String cookie : cookies) {
            List<HttpCookie> values = HttpCookie.parse(cookie);
            for(HttpCookie value : values) {
                str_cookies += (value.getName() + "=" + value.getValue() + ";");
            }
        }

        return str_cookies;
    }

    private class GetRequestThread extends RequestThread
    {
        public GetRequestThread() {
        }

        @Override
        public void run() {

            try {
                code = connection.getResponseCode();
                response = null;
                if (code == HttpURLConnection.HTTP_OK) {
                    response = readFromStream(connection.getInputStream());
                } else if (code >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    response = readFromStream(connection.getErrorStream());
                }

                response_headers = connection.getHeaderFields();
            }
            catch (java.io.IOException e) {
                Logger.E( TAG,  e.getClass().getSimpleName() + ": " + e.getMessage() );
            }
            finally {
                connection.disconnect();
            }

        }

    }

    private class PostRequestThread extends RequestThread
    {
        public PostRequestThread() {}

        @Override
        public void run() {
            try {

                OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8);
                if(multipartItems.isEmpty())
                    //writer.write(body);
                    connection.getOutputStream().write(body);
                else {
                    writeMultiPartData(connection.getOutputStream());
                }
                writer.flush();

                code = connection.getResponseCode();
                response = null;
                if (code == HttpURLConnection.HTTP_OK) {
                    response = readFromStream(connection.getInputStream());
                } else if (code >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    response = readFromStream(connection.getErrorStream());
                }

                response_headers = connection.getHeaderFields();
            }
            catch (java.io.IOException e) {
                Logger.E( TAG,  e.getClass().getSimpleName() + ": " + e.getMessage() );
            }
            catch (java.lang.Exception e) {
                Logger.E( TAG,  e.getClass().getSimpleName() + ": " + e.getMessage() );
            }
            finally {
                connection.disconnect();
            }
        }
    }

    public String[] getKeysFromResponseHeaders() {
        if(response_headers == null  || response_headers.size() == 0) return null;

        ArrayList<String> values = new ArrayList<String>();
        for(Map.Entry<String, List<String>> entry : response_headers.entrySet()) {
            for(String item : entry.getValue()) {
                values.add(entry.getKey());
            }
        }


        Object[] array_values = values.toArray();
        return Arrays.copyOf(array_values, array_values.length, String[].class);
    }

    public String[] getValuesFromResponseHeaders() {
        if(response_headers == null || response_headers.size() == 0) return null;

        ArrayList<String> values = new ArrayList<String>();
        for(Map.Entry<String, List<String>> entry : response_headers.entrySet()) {
            values.addAll(entry.getValue());
        }

        Object[] array_values = values.toArray();
        return Arrays.copyOf(array_values, array_values.length, String[].class);
    }

    public byte[] getResponseBody() {
        return responseBody;
    }

    /**
     * Encodes the 128 bit (16 bytes) MD5 digest into a 32 characters long
     * <CODE>String</CODE> according to RFC 2617.
     *
     * @param binaryData array containing the digest
     * @return encoded MD5, or <CODE>null</CODE> if encoding failed
     */
    private static String encode(byte[] binaryData) {
        if (binaryData.length != 16) {
            return null;
        }
        char[] buffer = new char[32];
        for (int i = 0; i < 16; i++) {
            int low = (binaryData[i] & 0x0f);
            int high = ((binaryData[i] & 0xf0) >> 4);
            buffer[i * 2] = HEXADECIMAL[high];
            buffer[(i * 2) + 1] = HEXADECIMAL[low];
        }
        return new String(buffer);
    }

    public static byte[] getAsciiBytes(final String data) {
        if (data == null) {
            throw new IllegalArgumentException("Parameter may not be null");
        }

        return data.getBytes(StandardCharsets.US_ASCII);
    }


    public static String calculateNonce() throws NoSuchAlgorithmException, UnsupportedEncodingException {
        String date = Long.toString(System.currentTimeMillis());

        MessageDigest hash = MessageDigest.getInstance("MD5");
        byte[] cnonce = hash.digest(date.getBytes("UTF-8"));
        return encode(cnonce);
    }

    private HashMap<String, String> parseHeader(String headerString) {
        String headerStringWithoutScheme = headerString.substring(headerString.indexOf(" ") + 1).trim();
        HashMap<String, String> values = new HashMap<String, String>();
        String keyValueArray[] = headerStringWithoutScheme.split(",");
        for (String keyval : keyValueArray) {
            if (keyval.contains("=")) {
                String key = keyval.substring(0, keyval.indexOf("="));
                String value = keyval.substring(keyval.indexOf("=") + 1);
                values.put(key.trim(), value.replaceAll("\"", "").trim());
            }
        }
        return values;
    }

    private String getQopVariantString() {
        String qopOption;
        if (auth_storage.qopVariant == AuthSettings.QOP_AUTH_INT) {
            qopOption = "auth-int";
        } else {
            qopOption = "auth";
        }
        return qopOption;
    }


    private String CreateDigest() throws NoSuchAlgorithmException, UnsupportedEncodingException, IllegalStateException {
        String uri = auth_storage.uri;
        String realm = auth_storage.realm;
        String nonce = auth_storage.nonce;
        String method = auth_storage.method;
        String algorithm = auth_storage.algo;
        if (uri == null) {
            throw new IllegalStateException("URI may not be null");
        }
        if (realm == null) {
            throw new IllegalStateException("Realm may not be null");
        }
        if (nonce == null) {
            throw new IllegalStateException("Nonce may not be null");
        }

        // If an algorithm is not specified, default to MD5.
        if (algorithm == null) {
            algorithm = "MD5";
        }
        // If an charset is not specified, default to ISO-8859-1.
        String charset = auth_storage.charset;
        if (charset == null) {
            charset = "ISO-8859-1";
        }

        if (auth_storage.qopVariant == AuthSettings.QOP_AUTH_INT) {
            throw new IllegalStateException("Unsupported qop in HTTP Digest authentication");
        }

        MessageDigest md5Helper =  MessageDigest.getInstance("MD5");
        String uname = auth_storage.user;
        String pwd = auth_storage.pwd;

        StringBuilder tmp = new StringBuilder(uname.length() + realm.length() + pwd.length() + 2);
        tmp.append(uname);
        tmp.append(':');
        tmp.append(realm);
        tmp.append(':');
        tmp.append(pwd);

        String a1 = tmp.toString();

        if(algorithm.equalsIgnoreCase("MD5-sess")) { // android-changed: ignore case
            // H( unq(username-value) ":" unq(realm-value) ":" passwd )
            //      ":" unq(nonce-value)
            //      ":" unq(cnonce-value)
            String cnonce = auth_storage.cnonce;

            String tmp2 = encode(md5Helper.digest(a1.getBytes(charset)));
            StringBuilder tmp3 = new StringBuilder(tmp2.length() + nonce.length() + cnonce.length() + 2);
            tmp3.append(tmp2);
            tmp3.append(':');
            tmp3.append(nonce);
            tmp3.append(':');
            tmp3.append(cnonce);
            a1 = tmp3.toString();
        } else if (!algorithm.equalsIgnoreCase("MD5")) { // android-changed: ignore case
            throw new IllegalStateException("Unhandled algorithm " + algorithm + " requested");
        }

        String md5a1 = encode(md5Helper.digest(a1.getBytes(charset)));
        String a2 = null;
        if (auth_storage.qopVariant == AuthSettings.QOP_AUTH_INT) {
            // Unhandled qop auth-int
            //we do not have access to the entity-body or its hash
            //TODO: add Method ":" digest-uri-value ":" H(entity-body)
        } else {
            a2 = method + ':' + uri;
        }

        String md5a2 = encode(md5Helper.digest(getAsciiBytes(a2)));

        String serverDigestValue;
        if (auth_storage.qopVariant == AuthSettings.QOP_MISSING) {
            StringBuilder tmp2 = new StringBuilder(md5a1.length() + nonce.length() + md5a2.length());
            tmp2.append(md5a1);
            tmp2.append(':');
            tmp2.append(nonce);
            tmp2.append(':');
            tmp2.append(md5a2);
            serverDigestValue = tmp2.toString();
        } else {
            String qopOption = getQopVariantString();
            String cnonce = auth_storage.cnonce;

            StringBuilder tmp2 = new StringBuilder(md5a1.length() + nonce.length()
                    + auth_storage.nc.length() + cnonce.length() + qopOption.length() + md5a2.length() + 5);
            tmp2.append(md5a1);
            tmp2.append(':');
            tmp2.append(nonce);
            tmp2.append(':');
            tmp2.append(auth_storage.nc);
            tmp2.append(':');
            tmp2.append(cnonce);
            tmp2.append(':');
            tmp2.append(qopOption);
            tmp2.append(':');
            tmp2.append(md5a2);
            serverDigestValue = tmp2.toString();
        }

        String serverDigest =
                encode(md5Helper.digest(getAsciiBytes(serverDigestValue)));
        return serverDigest;

    }

    private void AuthInit(URL _url, HashMap<String, String> values) throws NoSuchAlgorithmException, UnsupportedEncodingException, IllegalStateException  {
        auth_storage.method = connection.getRequestMethod();
        auth_storage.nonce = values.get("nonce");
        auth_storage.opaque = values.get("opaque");
        auth_storage.algo = values.get("algorithm");
        auth_storage.qop = values.get("qop");
        auth_storage.realm = values.get("realm");
        auth_storage.cnonce =  calculateNonce();;
        auth_storage.uri = _url.getPath();
        auth_storage.charset = values.get("charset");

        boolean unsupportedQop = false;
        if (auth_storage.qop != null) {
            StringTokenizer tok = new StringTokenizer(auth_storage.qop,",");
            while (tok.hasMoreTokens()) {
                String variant = tok.nextToken().trim();
                if (variant.equals("auth")) {
                    auth_storage.qopVariant = AuthSettings.QOP_AUTH;
                    break; //that's our favourite, because auth-int is unsupported
                } else if (variant.equals("auth-int")) {
                    auth_storage.qopVariant = AuthSettings.QOP_AUTH_INT;
                } else {
                    unsupportedQop = true;
                }
            }
        }

        if (unsupportedQop && (auth_storage.qopVariant == AuthSettings.QOP_MISSING)) {
            throw new IllegalStateException("None of the qop methods is supported");
        }

        if (auth_storage.realm == null) {
            throw new IllegalStateException("missing realm in realm");
        }

        if (auth_storage.nonce == null) {
            throw new IllegalStateException("missing nonce in realm");
        }
    }

    private boolean BasiAuth(HashMap<String, String> values) throws java.io.IOException, java.lang.InterruptedException {
        try {

            URL _url = new URL(url);
            auth_storage.realm = values.get("realm");
            if(auth_storage.user == null)
                return false;
            if(auth_storage.pwd == null)
                return false;

            String str = auth_storage.user + ":" + auth_storage.pwd;
            String str64 = Base64.getEncoder().encodeToString(str.getBytes());

            auth_storage.authHeader = String.format("Basic %s", str64);
            return true;
        }
        catch (IllegalStateException e) {
            Logger.E( TAG,  e.getClass().getSimpleName() + ": " + e.getMessage() );
            return false;
        }
    }

    private boolean DigestAuth(HashMap<String, String> values) throws java.io.IOException, java.lang.InterruptedException {
        try {

            URL _url = new URL(url);
            AuthInit(_url, values);
            auth_storage.serverResponse = CreateDigest();
        }
        catch (NoSuchAlgorithmException e) {
            Logger.E( TAG,  e.getClass().getSimpleName() + ": " + e.getMessage() );
            return false;
        }
        catch (IllegalStateException e) {
            Logger.E( TAG,  e.getClass().getSimpleName() + ": " + e.getMessage() );
            return false;
        }


        auth_storage.authHeader = String.format("Digest username=\"%s\", realm=\"%s\", " +
                        "nonce=\"%s\", uri=\"%s\", qop=auth, nc=%s, cnonce=\"%s\", " +
                        "response=\"%s\"",
                auth_storage.user, auth_storage.realm, auth_storage.nonce, auth_storage.uri, auth_storage.nc, auth_storage.cnonce, auth_storage.serverResponse);

        if(auth_storage.opaque != null)
            auth_storage.authHeader += String.format(", opaque=\"%s\"", auth_storage.opaque);
        if(auth_storage.algo != null)
            auth_storage.authHeader += String.format(", algorithm=\"%s\"", auth_storage.algo);
        if(auth_storage.charset != null)
            auth_storage.authHeader += String.format(", charset=\"%s\"", auth_storage.charset);

        return true;
    }

    private int Authetentificate() throws java.io.IOException, java.lang.InterruptedException {

        List<String> headers = response_headers.get("WWW-Authenticate");
        HashMap<String, String> values = null;
        if(headers != null && !headers.isEmpty())
            values = parseHeader(headers.get(0));
        else
            return HttpURLConnection.HTTP_UNAUTHORIZED;

        if(auth_storage.authType == AuthSettings.HTTP_DIGEST) {
            if(!DigestAuth(values))
                return HttpURLConnection.HTTP_UNAUTHORIZED;
        }
        else if(auth_storage.authType == AuthSettings.HTTP_BASIC) {
            if(!BasiAuth(values))
                return HttpURLConnection.HTTP_UNAUTHORIZED;
        }
        else
            return HttpURLConnection.HTTP_UNAUTHORIZED;

        if(method.equals("POST")) {
           int code = postData(true);
           return code;
        }
        else {
           int code = getData(true);
           return code;
        }
    }

    private int postData(boolean auth) throws java.io.IOException, java.lang.InterruptedException {
        URL _url = new URL(url);
        response_headers = null;
        connection = getConnection(_url);
        connection.setReadTimeout((int)30000);
        connection.setConnectTimeout(30000);
        connection.setRequestMethod(method);
        connection.setDoOutput(true);
        connection.setUseCaches(false);

        if(!multipartItems.isEmpty()) {
            long size = getMultiPartDataSize();
            connection.setRequestProperty("Content-Length", Long.toString(size));
            connection.setFixedLengthStreamingMode(size);
        }
        else {
            if(body.length > 0)
                connection.setFixedLengthStreamingMode(body.length);
        }
        fillHeaders();

        PostRequestThread post_thread = new PostRequestThread();
        post_thread.start();
        post_thread.join();
        int responseCode = post_thread.getResponseCode();
        responseBody = post_thread.getResponse();
        response_headers = post_thread.getResponseHeaders();

        if(responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && !auth) {
            responseCode = Authetentificate();
            auth_storage.authHeader = null;
        }

        multipartItems.clear();
        headers.clear();

        return responseCode;
    }

    class RhoHostVerifier implements HostnameVerifier {
        @Override
        public  boolean verify(String hostname, SSLSession session) {
            return true;
        }
    }

    private HttpURLConnection getConnection(URL u) throws java.io.IOException {
        try {
            HttpURLConnection conn = null;
            if (u.getProtocol().toLowerCase().matches("https")) {
                conn = (HttpsURLConnection) u.openConnection();
                ((HttpsURLConnection) conn).setSSLSocketFactory(SSLImpl.getFactory(sslVerify));
                ((HttpsURLConnection) conn).setHostnameVerifier(new RhoHostVerifier());
            }
            else {
                conn = (HttpURLConnection) u.openConnection();
            }
            return conn;
        }
        catch (GeneralSecurityException e) {
            Logger.E( TAG,  e.getClass().getSimpleName() + ": " + e.getMessage() );
            return null;
        }
    }

    private int getData(boolean auth) throws java.io.IOException, java.lang.InterruptedException {
        URL _url = new URL(url);
        response_headers = null;
        connection = getConnection(_url);
        connection.setReadTimeout(30000);
        connection.setConnectTimeout(30000);
        connection.setRequestMethod(method);
        fillHeaders();

        GetRequestThread get_thread = new GetRequestThread();
        get_thread.start();
        get_thread.join();
        int responseCode = get_thread.getResponseCode();
        responseBody = get_thread.getResponse();
        response_headers = get_thread.getResponseHeaders();

        if(responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && !auth) {
            responseCode = Authetentificate();
            auth_storage.authHeader = null;
        }

        headers.clear();

        return responseCode;
    }


} 