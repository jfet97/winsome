package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import domain.error.Error;
import http.HttpRequest;
import http.HttpResponse;

public class Server {
  public static void main(String[] args) {
    var server = new Server();

    var t = new Thread(() -> {
      try {
        server.run();
      } catch (Exception e) {
        e.printStackTrace();
      }
    });

    t.start();
    try {
      t.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private static final int DEFAULT_PORT = 12345;

  public HttpResponse makeBadResponse(String error) {
    var errorJSON = Error.of(error).toJSON();

    return HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_400[0], HttpResponse.CODE_400[1])
        .flatMap(r -> r.setHeader("Connection", "close"))
        .flatMap(r -> r.setHeader("Content-Length", errorJSON.getBytes().length + ""))
        .flatMap(r -> r.setBody(errorJSON))
        .get();
  }

  public HttpResponse makeServerErrorResponse() {
    var errorJSON = Error.of("INTERNAL SERVER ERROR").toJSON();

    return HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_500[0], HttpResponse.CODE_500[1])
        .flatMap(r -> r.setHeader("Connection", "close"))
        .flatMap(r -> r.setHeader("Content-Length", errorJSON.getBytes().length + ""))
        .flatMap(r -> r.setBody(errorJSON))
        .get();
  }

  public HttpResponse makeOkResponse(String body) {
    return HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_200[0], HttpResponse.CODE_200[1])
        .flatMap(r -> r.setHeader("Connection", "keep-alive"))
        .flatMap(r -> r.setHeader("Content-Length", body.getBytes().length + ""))
        .flatMap(r -> r.setBody(body))
        .get();
  }

  public void run() throws Exception {

    var port = DEFAULT_PORT;
    var ip = "192.168.1.113";
    var CRLF = "\r\n";
    var CRLFx2Byte = new byte[] { 0x0D, 0x0A, 0x0D, 0x0A };

    // set up the server
    try (var serverChannel = ServerSocketChannel.open();
        var selector = Selector.open();
        var serverSocket = serverChannel.socket();) {

      var address = new InetSocketAddress(ip, port);
      serverSocket.bind(address);
      serverChannel.configureBlocking(false);

      // listen for incoming clients
      serverChannel.register(selector, SelectionKey.OP_ACCEPT);

      // shared, fixed buffer between requests (safe because NIO input reading is
      // single-threaded)
      // var buf = ByteBuffer.allocate(16384);
      var buf = ByteBuffer.allocate(100);

      while (true) {
        try {

          // wait an event from the selector
          selector.select();

          var it = selector.selectedKeys().iterator();

          while (it.hasNext()) {
            var key = it.next();

            try {

              if (key.isAcceptable()) {

                System.out.print("new client\n");

                // accept the connetion, returns a channel that is properly configured
                var client = serverChannel.accept();
                client.configureBlocking(false);

                // firstly, we have to read a message from the client
                var newClientKey = client.register(selector, SelectionKey.OP_READ);

                // create the context associated with the channel with enough space
                var clientCtx = RequestContext.of();

                // attach the context to the channel
                newClientKey.attach(clientCtx);

              } else if (key.isReadable()) {

                var clientCtx = (RequestContext) key.attachment();
                var client = (SocketChannel) key.channel();

                // clear the shared buffer
                buf.clear();
                // read a bit of the incoming message into the shared buffer
                var bytesRead = client.read(buf);

                if (bytesRead < 0) {
                  // client has closed the connection
                  key.cancel();
                  client.close();
                  continue;
                }

                var error = "";

                // concat what has been read into the client's buffer
                var bufArray = buf.array();
                var bufDataLen = buf.position();
                clientCtx.concatBuffer(bufArray, bufDataLen);

                if (clientCtx.headersParsed == false) {
                  // "Content-Length" header not yet parsed

                  // the index of the first carriage return if the CR LF CR LF sequence is found
                  var separatorIndex = clientCtx.bufferContains(CRLFx2Byte);

                  // CR LF CR LF sequence found
                  if (separatorIndex != -1) {

                    System.out.println("---------\n" + clientCtx.bufferToString(true) + "\n-----------------\n");

                    var ereq = HttpRequest.parse(clientCtx.bufferToString(true));

                    // invalid http request because parser has failed
                    if (ereq.isLeft()) {
                      error = "invalid http request: " + ereq.getLeft();
                    } else {
                      var req = ereq.get();

                      var isGet = req.getMethod().equals("GET");
                      var contentLengthHeader = req.getHeaders().get("Content-Length");
                      var isThereContentLengthHeader = contentLengthHeader != null;

                      if (isThereContentLengthHeader) {

                        try {
                          var contentLength = Integer.parseInt(contentLengthHeader);

                          var afterindex = separatorIndex + 4; // skip CR LF CR LF
                          var bytesStored = clientCtx.getBufferStoredBytes();

                          if (afterindex >= bytesStored) {
                            clientCtx.yetToRead = contentLength;
                          } else {
                            clientCtx.yetToRead = contentLength - (bytesStored - afterindex);
                          }
                        } catch (NumberFormatException e) {
                          // string -> int conversion failed
                          error = "malformed Content-Length header";
                        }

                      } else if (!isGet) {
                        // if there is no Content-Length header and the request is not a GET request
                        // the server does not accept that request
                        error = "missing Content-Length header";
                      } else {
                        clientCtx.yetToRead = 0;
                      }
                    }
                    clientCtx.headersParsed = true;
                  }

                } else {
                  // "Content-Length" header already parsed
                  clientCtx.yetToRead -= bufDataLen;
                }

                if (!error.equals("")) {
                  // something was wrong with this request
                  // the server has to reply with an appropriate http response to then close the
                  // connection

                  clientCtx.isError = true;
                  clientCtx.setResponse(makeBadResponse(error));

                  // deregister OP_READ, register OP_WRITE
                  key.interestOps(SelectionKey.OP_WRITE);

                } else if (clientCtx.yetToRead == 0) {
                  // nothing more to read

                  // TODO: now create an HttpRequest to be handled by thread pool
                  var ereq = HttpRequest.parse(clientCtx.bufferToString(true));

                  // invalid http request because parser has failed
                  if (ereq.isLeft()) {

                    clientCtx.isError = true;
                    clientCtx.setResponse(makeBadResponse("invalid http request: " + ereq.getLeft()));

                  } else {
                    var req = ereq.get();

                    clientCtx.setResponse(makeOkResponse(
                        "<!DOCTYPE html><html><body><h1>Your request was:</h1></br>" + req.toString() + "</body></html>"));
                  }

                  // deregister OP_READ, register OP_WRITE
                  key.interestOps(SelectionKey.OP_WRITE);
                } else {
                  // we haven't read the whole request yet
                }

              } else if (key.isWritable()) {

                var clientCtx = (RequestContext) key.attachment();
                var client = (SocketChannel) key.channel();

                if (clientCtx.getResponseBuffer().isEmpty()) {

                  clientCtx.isError = true;
                  clientCtx.setResponse(makeServerErrorResponse());

                }

                var resBuf = clientCtx.getResponseBuffer().get();

                if (!resBuf.hasRemaining()) {
                  // for the next request
                  clientCtx.clear();

                  if (clientCtx.isError) {
                    key.cancel();
                    client.close();
                  } else {
                    key.interestOps(SelectionKey.OP_READ);
                  }

                } else {
                  client.write(resBuf);
                }

              }

              // remove the key from the selected set, but not from the registered set
              it.remove();

            } catch (Exception e) {
              e.printStackTrace();
              key.cancel();
              var channelToClose = key.channel();

              if (channelToClose != serverChannel) {
                try {
                  channelToClose.close();
                } catch (IOException e1) {
                  e1.printStackTrace();
                }
              }
            }

          }

        } catch (Exception ex) {
          ex.printStackTrace();
        }
      }

    } catch (

    Exception e) {
      e.printStackTrace();
    }
  }
}
