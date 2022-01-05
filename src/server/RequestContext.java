package server;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import http.HttpResponse;
import io.vavr.control.Option;

// used as attachment to the channels handled by NIO
public class RequestContext {
  // used to store the request
  private byte[] requestBuffer = new byte[128];
  // first free slot into the requestBuffer
  private Integer head = 0;

  // counter used to store the content length value
  public Integer yetToRead = -1;
  // this flag indicates an error during some process
  public Boolean isError = false;
  // this flags indicates if the headers of the request
  // have already been parsed
  public Boolean headersParsed = false;
  // used to store the HTTP response to be sent
  private HttpResponse response = null;
  // used to store the bytes of the HTTP response to be sent
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

  // store new content into the request buffer, increasing its size when necessary
  public void concatRequestBufferWith(byte[] another, int amount) {

    if (amount > requestBuffer.length - head) {
      requestBuffer = Arrays.copyOf(requestBuffer, (int) ((requestBuffer.length + amount) * 1.618033));
    }

    for (var i = 0; i < amount; i++, head++) {
      requestBuffer[head] = another[i];
    }
  }

  // transform the content of the request buffer into a string
  public String requestBufferToString(Boolean useUTF8) {

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

  // check if the request buffer contain a sequence of bytes
  public Integer requestBufferContains(byte[] sub) {

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

  public Integer requestBufferContentSize() {
    return this.head;
  }

  public byte[] getRequestBuffer() {
    return requestBuffer.clone();
  }

  // clear the whole instance to be used for
  // further requests from the same client
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
