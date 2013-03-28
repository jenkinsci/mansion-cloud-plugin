package com.cloudbees.jenkins.plugins.mtslavescloud.client;

import net.sf.json.JSONObject;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class RemoteReference {
    /**
     * URL of  this resource.
     */
    public final URL url;

    /*package*/ RemoteReference(URL url) {
        this.url = url;
    }

    protected HttpURLConnection open(String relUrl) throws IOException {
        URL u = url;
        if (url.toString().endsWith("/"))
            u = new URL(u,relUrl);
        else
            u = new URL(u.toExternalForm()+'/'+relUrl);
        return (HttpURLConnection)u.openConnection();
    }

    protected HttpURLConnection postJson(HttpURLConnection con, JSONObject json) throws IOException {
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        con.connect();

        // send JSON
        OutputStreamWriter w = new OutputStreamWriter(con.getOutputStream(), "UTF-8");
        json.write(w);
        w.close();

        return con;
    }

    protected void verifyResponseStatus(HttpURLConnection con) throws IOException {
        if (con.getResponseCode()/100!=2)
            throw new IOException("Failed to call "+con.getURL()+" : "+con.getResponseCode()+' '+con.getResponseMessage());
    }
}
