package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import domain.constant.Constant;
import domain.error.Error;
import http.HttpRequest;
import http.HttpResponse;
import io.vavr.control.Either;
import jexpress.JExpress;

public class Server {
  public static void main(String[] args) {

    var port = 12345;
    var ip = "192.168.1.113";

    var server = new Server();

    var t = new Thread(() -> {
      try {
        server.run(port, ip);
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

  private JExpress jexpress = JExpress.of();

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

  public void handleAccept(ServerSocketChannel serverChannel, Selector selector) throws IOException {
    // accept the connetion, returns a channel that is properly configured
    var client = serverChannel.accept();
    client.configureBlocking(false);

    // firstly, we have to read a message from the client
    var newClientKey = client.register(selector, SelectionKey.OP_READ);

    // create the context associated with the channel with enough space
    var clientCtx = RequestContext.of();

    // attach the context to the channel
    newClientKey.attach(clientCtx);
  }

  public void handleWrite(SelectionKey key) throws IOException {
    var clientCtx = (RequestContext) key.attachment();
    var client = (SocketChannel) key.channel();

    // something weird has happened
    if (clientCtx.getResponseBuffer().isEmpty()) {
      clientCtx.isError = true;
      clientCtx.setResponse(makeServerErrorResponse());
    }

    var resBuf = clientCtx.getResponseBuffer().get();

    if (!resBuf.hasRemaining()) {
      // clean it up for the next request
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

  public Either<String, Integer> searchCRLFx2(RequestContext clientCtx) {

    var error = "";
    var toRet = -1;

    // the index of the first carriage return if the CR LF CR LF sequence is present
    var separatorIndex = clientCtx.bufferContains(Constant.CRLFx2Byte);

    // CR LF CR LF sequence was found
    if (separatorIndex != -1) {

      // parsing of the partial request to extract the Content-Length header
      var ereq = HttpRequest.parse(clientCtx.bufferToString(true));

      // invalid http request because parser has failed
      if (ereq.isLeft()) {
        error = "invalid http request: " + ereq.getLeft();
      } else {
        // get a valid request instance
        var req = ereq.get();

        var isGet = req.getMethod().equals("GET");
        var contentLengthHeader = req.getHeaders().get("Content-Length");
        var isThereContentLengthHeader = contentLengthHeader != null;

        if (isThereContentLengthHeader) {

          try {
            var contentLength = Integer.parseInt(contentLengthHeader);

            // skip CR LF CR LF
            var afterindex = separatorIndex + 4;
            // how many bytes of the request have been read
            var bytesStored = clientCtx.getBufferStoredBytes();

            // compute the number of missing bytes to read
            if (afterindex == bytesStored) {
              // CR LF CR LF sequence was at the very end of the buffer,
              // so we have to read the whole body
              toRet = contentLength;
            } else if (afterindex < bytesStored) {
              // CR LF CR LF sequence was not at the end of the buffer,
              // so we have already read some bytes of the body
              toRet = contentLength - (bytesStored - afterindex);
            } else {
              // afterindex > bytesStored is impossible because in the
              // worst case the CR LF CR LF sequence was at the very end
              // of the buffer, so afterindex would be equal but not greather than bytesStored
              throw new RuntimeException("the impossible has happened");
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
          // GET without payload: we have encountered the CR LF CR LF
          // sequence => we have just read the whole request
          toRet = 0;
        }
      }
      clientCtx.headersParsed = true;
    } else {
      // we haven't read enough bytes of the request yet
    }

    if (error.equals("")) {
      return Either.right(toRet);
    } else {
      return Either.left(error);
    }

  }

  public void handleRead(SelectionKey key, ByteBuffer buf, Selector selector) throws IOException {

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
      return;
    }

    var error = "";

    // concat what has been read into the client's buffer
    var bufArray = buf.array();
    var bufDataLen = buf.position();
    clientCtx.concatBuffer(bufArray, bufDataLen);

    if (clientCtx.headersParsed == false) {
      // Content-Length header not yet parsed, we are looking for the CR LF CR LF
      // sequence that divides the last header from the body
      // We need to extract the Content-Length value to know how long is the body of
      // the request
      var eSearchRes = this.searchCRLFx2(clientCtx);
      if (eSearchRes.isLeft()) {
        error = eSearchRes.getLeft();
      } else {
        clientCtx.yetToRead = eSearchRes.get();
      }
    } else {
      // "Content-Length" header already parsed, just update
      // how many bytes are missing to read the whole request
      clientCtx.yetToRead -= bufDataLen;
    }

    if (!error.equals("")) {
      // something iswrong with this request
      // the server has to reply with an appropriate
      // http response to then close the connection
      clientCtx.isError = true;
      clientCtx.setResponse(makeBadResponse(error));

      // deregister OP_READ, register OP_WRITE
      key.interestOps(SelectionKey.OP_WRITE);

    } else if (clientCtx.yetToRead == 0) {
      // nothing more to read
      var ereq = HttpRequest.parse(clientCtx.bufferToString(true));

      if (ereq.isLeft()) {
        // invalid http request because the parser has failed
        clientCtx.isError = true;
        clientCtx.setResponse(makeBadResponse("invalid http request: " + ereq.getLeft()));

        // deregister OP_READ, register OP_WRITE
        key.interestOps(SelectionKey.OP_WRITE);

      } else {
        // get a valid request instance
        var req = ereq.get();

        // handle using a common thread pool
        var reqResult = CompletableFuture.supplyAsync(() -> jexpress.handle(req));

        reqResult.thenAccept(eres -> {

          // set the http response accordingly to the jexpress result
          clientCtx.setResponse(
              eres.fold(
                  err -> makeBadResponse(err),
                  res -> res));

          // deregister OP_READ, register OP_WRITE
          key.interestOps(SelectionKey.OP_WRITE);

          // needed because we update the interests set asynchronously
          selector.wakeup();

        });

      }

    } else {
      // we haven't read the whole request yet
      // and no error has occurred
    }

  }

  public void run(int port, String ip) throws Exception {

    // set up the server
    try (var serverChannel = ServerSocketChannel.open();
        var selector = Selector.open();
        var serverSocket = serverChannel.socket();) {

      // server config
      var address = new InetSocketAddress(ip, port);
      serverSocket.bind(address);
      serverChannel.configureBlocking(false);

      // listen for incoming clients
      serverChannel.register(selector, SelectionKey.OP_ACCEPT);

      // shared, fixed buffer between requests
      // (safe because NIO input reading is single-threaded)
      var buf = ByteBuffer.allocate(16384);

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

                this.handleAccept(serverChannel, selector);

              } else if (key.isReadable()) {

                this.handleRead(key, buf, selector);

              } else if (key.isWritable()) {

                this.handleWrite(key);

              }

              // remove the key from the selected set, but not from the registered set
              it.remove();
            } catch (Exception e) {
              e.printStackTrace();
              key.cancel();
              var channelToClose = key.channel();

              // serverChannel is autocloseable (try-with-resources)
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
