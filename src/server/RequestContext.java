package server;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import http.HttpResponse;
import io.vavr.control.Option;

public class RequestContext {
  private byte[] requestBuffer = new byte[128];
  private Integer head = 0; // first free slot

  public Integer yetToRead = -1;
  public Boolean isError = false;
  public Boolean headersParsed = false;
  private HttpResponse response = null;
  private ByteBuffer responseBuffer = null;

  public void setResponse(HttpResponse res) {
    if (res != null) {
      this.response = res;
      this.responseBuffer = ByteBuffer.wrap(res.toString().getBytes());
    }
  }

  public Option<HttpResponse> getResponse() {
    return Option.of(this.response);
  }

  public Option<ByteBuffer> getResponseBuffer() {
    return Option.of(this.responseBuffer);
  }

  public void concatBuffer(byte[] another, int amount) {

    if (amount > requestBuffer.length - head) {
      requestBuffer = Arrays.copyOf(requestBuffer, (int) ((requestBuffer.length + amount) * 1.618033));
    }

    for (var i = 0; i < amount; i++, head++) {
      requestBuffer[head] = another[i];
    }
  }

  public String bufferToString(Boolean useUTF8) {

    var validSubset = Arrays.copyOfRange(requestBuffer, 0, this.head);
    try {
      if (useUTF8) {
        return new String(validSubset, "UTF-8");
      } else {
        return new String(validSubset);
      }
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
      return "";
    }
  }

  public Integer bufferContains(byte[] sub) {

    try {
      for (var i = 0; i < requestBuffer.length; i++) {
        var found = true;

        for (var j = 0; j < sub.length && found; j++) {
          if (requestBuffer[i + j] != sub[j]) {
            found = false;
          }
        }

        if (found) {
          return i;
        }

      }

      return -1;
    } catch (IndexOutOfBoundsException e) {
      return -1;
    }

  }

  public Integer getBufferStoredBytes() {
    return this.head;
  }

  public byte[] getBuffer() {
    return requestBuffer.clone();
  }

  public void clear() {
    this.requestBuffer = new byte[128];
    this.head = 0;
    this.yetToRead = -1;
    this.isError = false;
    this.headersParsed = false;
    this.response = null;
    this.responseBuffer = null;
  }

  public static RequestContext of() {
    return new RequestContext();
  }

}
