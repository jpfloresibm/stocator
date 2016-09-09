/**
 * (C) Copyright IBM Corp. 2015, 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.stocator.fs.swift;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.InputStreamEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.stocator.fs.swift.auth.JossAccount;
import com.ibm.stocator.fs.swift.http.SwiftConnectionManager;

/**
 *
 * Wraps OutputStream
 * This class is not thread-safe
 *
 */
public class SwiftOutputStream extends OutputStream {
  /*
   * Logger
   */
  private static final Logger LOG = LoggerFactory.getLogger(SwiftOutputStream.class);
  /*
   * Streaming chunk size
   */
  private static final int STREAMING_CHUNK = 8 * 1024 * 1024;
  /*
   * Read time out
   */
  private static final int READ_TIMEOUT = 100 * 1000;
  /*
   * Output stream
   */
  private OutputStream mOutputStream;
  /*
   * Access url
   */
  private URL mUrl;

  /*
   * Client used
   */
  private HttpClient client;

  /*
   * Request
   */
  private HttpPut request;

  private Thread writeThread;
  private long totalWritten;

  /**
   * Default constructor
   *
   * @param account JOSS account object
   * @param url URL connection
   * @param contentType content type
   * @param metadata input metadata
   * @throws IOException if error
   */
  public SwiftOutputStream(JossAccount account, URL url, final String contentType,
                           Map<String, String> metadata, SwiftConnectionManager connectionManager)
          throws IOException {
    mUrl = url;
    totalWritten = 0;
    client = connectionManager.createHttpConnection();
    request = new HttpPut(mUrl.toString());
    request.addHeader("X-Auth-Token", account.getAuthToken());
    if (metadata != null && !metadata.isEmpty()) {
      for (Map.Entry<String, String> entry : metadata.entrySet()) {
        request.addHeader("X-Object-Meta-" + entry.getKey(), entry.getValue());
      }
    }

    PipedOutputStream out = new PipedOutputStream();
    final PipedInputStream in = new PipedInputStream();
    out.connect(in);
    mOutputStream = out;
    writeThread = new Thread() {
      public void run() {
        InputStreamEntity entity = new InputStreamEntity(in, -1);
        entity.setChunked(true);
        entity.setContentType(contentType);
        request.setEntity(entity);
        try {
          HttpResponse response = client.execute(request);
          int responseCode = response.getStatusLine().getStatusCode();
          if (responseCode >= 400) {
            LOG.warn("Http Error Code: {} for {}, \nReason:", responseCode, mUrl.toString(),
                    response.getStatusLine().getReasonPhrase());
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    };
  }

  private void startThread() {
    if (writeThread.getState().equals(Thread.State.NEW)) {
      writeThread.start();
    }
  }

  @Override
  public void write(int b) throws IOException {
    if (LOG.isTraceEnabled()) {
      totalWritten = totalWritten + 1;
      LOG.trace("Write {} one byte. Total written {}", mUrl.toString(), totalWritten);
    }
    startThread();
    mOutputStream.write(b);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (LOG.isTraceEnabled()) {
      totalWritten = totalWritten + len;
      LOG.trace("Write {} off {} len {}. Total {}", mUrl.toString(), off, len, totalWritten);
    }
    startThread();
    mOutputStream.write(b, off, len);
  }

  @Override
  public void write(byte[] b) throws IOException {
    if (LOG.isTraceEnabled()) {
      totalWritten = totalWritten + b.length;
      LOG.trace("Write {} len {}. Total {}", mUrl.toString(), b.length, totalWritten);
    }
    startThread();
    mOutputStream.write(b);
  }

  @Override
  public void close() throws IOException {
    startThread();
    LOG.trace("Close the output stream for {}", mUrl.toString());
    flush();
    mOutputStream.close();
    try {
      writeThread.join();
    } catch (InterruptedException ie) {
      ie.printStackTrace();
    }
  }

  @Override
  public void flush() throws IOException {
    LOG.trace("{} flush method", mUrl.toString());
    mOutputStream.flush();
  }
}
