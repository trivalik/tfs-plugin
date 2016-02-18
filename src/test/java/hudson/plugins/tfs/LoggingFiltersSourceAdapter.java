package hudson.plugins.tfs;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;

import java.util.ArrayList;
import java.util.List;

public class LoggingFiltersSourceAdapter extends HttpFiltersSourceAdapter {

    private final List<FullHttpRequest> requests = new ArrayList<FullHttpRequest>();
    private final List<FullHttpResponse> responses = new ArrayList<FullHttpResponse>();

    /*
    https://github.com/adamfisk/LittleProxy
    """
    To enable aggregator and inflater you have to return a value greater than 0 in your
    `HttpFiltersSource#get(Request/Response)BufferSizeInBytes()` methods.
    This provides to you a `FullHttp(Request/Response)`
    with the complete content in your filter uncompressed.
    Otherwise you have to handle the chunks yourself.
    """
     */

    @Override public int getMaximumRequestBufferSizeInBytes() {
        return 10 * 1024 * 1024;
    }

    @Override public int getMaximumResponseBufferSizeInBytes() {
        return 10 * 1024 * 1024;
    }

    @Override public HttpFilters filterRequest(final HttpRequest originalRequest, final ChannelHandlerContext ctx) {
        return new HttpFiltersAdapter(originalRequest, ctx) {
            @Override public HttpResponse clientToProxyRequest(final HttpObject httpObject) {
                requests.add((FullHttpRequest) httpObject);
                return /* "[return] null to continue processing as usual" */ null;
            }

            @Override public HttpObject serverToProxyResponse(final HttpObject httpObject) {
                responses.add((FullHttpResponse) httpObject);
                return /* [return] the unmodified HttpObject */ httpObject;
            }
        };
    }

    public boolean proxyWasUsed() {
        return requests.size() > 0;
    }

    public void reset() {
        requests.clear();
        responses.clear();
    }
}
