package com.apollographql.apollo.internal.reader;

import com.apollographql.apollo.ApolloCall;
import com.apollographql.apollo.ApolloClient;
import com.apollographql.apollo.ApolloPrefetch;
import com.apollographql.apollo.api.Operation;
import com.apollographql.apollo.api.Query;
import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.api.ResponseFieldMapper;
import com.apollographql.apollo.api.ResponseReader;
import com.apollographql.apollo.exception.ApolloException;
import com.apollographql.apollo.interceptor.ApolloInterceptor;
import com.apollographql.apollo.interceptor.ApolloInterceptorChain;
import com.apollographql.apollo.internal.RealApolloCall;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static com.google.common.truth.Truth.assertThat;

public class ApolloCallTrackerTest {

  private static final String SERVER_URL = "http://localhost:1234";
  private static final int TIMEOUT_SECONDS = 2;
  private static final Query EMPTY_QUERY = new Query() {
    @Override public String queryDocument() {
      return "";
    }

    @Override public Variables variables() {
      return EMPTY_VARIABLES;
    }

    @Override public ResponseFieldMapper<Data> responseFieldMapper() {
      return new ResponseFieldMapper<Data>() {
        @Override public Data map(ResponseReader responseReader) throws IOException {
          return null;
        }
      };
    }

    @Override public Object wrapData(Data data) {
      return data;
    }
  };

  private OkHttpClient.Builder okHttpClientBuilder;
  private MockWebServer server;

  @Before
  public void setUp() throws Exception {
    okHttpClientBuilder = new OkHttpClient
        .Builder();
    server = new MockWebServer();
  }

  @Test
  public void testRunningCallsCount_whenSyncPrefetchCallIsMade() throws InterruptedException {
    final CountDownLatch firstLatch = new CountDownLatch(1);
    final CountDownLatch secondLatch = new CountDownLatch(1);

    Interceptor interceptor = new Interceptor() {
      @Override public okhttp3.Response intercept(Chain chain) throws IOException {
        try {
          firstLatch.countDown();
          secondLatch.await();
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
        throw new IOException();
      }
    };

    OkHttpClient okHttpClient = okHttpClientBuilder
        .addInterceptor(interceptor)
        .build();

    ApolloClient apolloClient = ApolloClient.builder()
        .serverUrl(SERVER_URL)
        .okHttpClient(okHttpClient)
        .build();

    ApolloPrefetch prefetch = apolloClient.prefetch(EMPTY_QUERY);
    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(0);

    Thread thread = synchronousPrefetch(prefetch);

    firstLatch.await();
    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(1);

    secondLatch.countDown();
    thread.join();
    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(0);
  }

  @Test
  public void testRunningCallsCount_whenAsyncPrefetchCallIsMade() throws InterruptedException {
    final CountDownLatch latch = new CountDownLatch(1);
    ApolloClient apolloClient = ApolloClient.builder()
        .serverUrl(SERVER_URL)
        .okHttpClient(okHttpClientBuilder.build())
        .build();
    server.enqueue(createMockResponse());

    ApolloPrefetch prefetch = apolloClient.prefetch(EMPTY_QUERY);

    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(0);

    prefetch.enqueue(new ApolloPrefetch.Callback() {
      @Override public void onSuccess() {
        latch.countDown();
      }

      @Override public void onFailure(@Nonnull ApolloException e) {
      }
    });

    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(1);
    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(0);
  }

  @Test
  public void testRunningCallsCount_whenSyncApolloCallIsMade() throws InterruptedException {
    final CountDownLatch firstLatch = new CountDownLatch(1);
    final CountDownLatch secondLatch = new CountDownLatch(1);

    ApolloInterceptor interceptor = new ApolloInterceptor() {
      @Nonnull @Override
      public InterceptorResponse intercept(Operation operation, ApolloInterceptorChain chain) throws ApolloException {
        try {
          firstLatch.countDown();
          secondLatch.await();
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
        throw new ApolloException("ApolloException");
      }

      @Override
      public void interceptAsync(@Nonnull Operation operation, @Nonnull ApolloInterceptorChain chain,
          @Nonnull ExecutorService dispatcher, @Nonnull CallBack callBack) {
      }

      @Override public void dispose() {
      }
    };

    ApolloClient apolloClient = ApolloClient.builder()
        .serverUrl(SERVER_URL)
        .okHttpClient(okHttpClientBuilder.build())
        .addApplicationInterceptor(interceptor)
        .build();

    ApolloCall apolloCall = apolloClient.newCall(EMPTY_QUERY);
    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(0);

    Thread thread = synchronousApolloCall(apolloCall);

    firstLatch.await();
    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(1);

    secondLatch.countDown();
    thread.join();

    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(0);
  }

  @Test
  public void testRunningCallsCount_whenAsyncApolloCallIsMade() throws InterruptedException {
    final CountDownLatch latch = new CountDownLatch(1);
    ApolloClient apolloClient = ApolloClient.builder()
        .serverUrl(SERVER_URL)
        .okHttpClient(okHttpClientBuilder.build())
        .build();
    server.enqueue(createMockResponse());

    RealApolloCall<Object> apolloCall = (RealApolloCall<Object>) apolloClient.newCall(EMPTY_QUERY);

    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(0);

    apolloCall.enqueue(new ApolloCall.Callback<Object>() {
      @Override public void onResponse(@Nonnull Response<Object> response) {
        latch.countDown();
      }

      @Override public void onFailure(@Nonnull ApolloException e) {
      }
    });

    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(1);
    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(apolloClient.getRunningCallsCount()).isEqualTo(0);
  }

  private Thread synchronousApolloCall(final ApolloCall apolloCall) {
    Runnable runnable = new Runnable() {
      @Override public void run() {
        try {
          apolloCall.execute();
        } catch (ApolloException expected) {

        }
      }
    };
    return startThread(runnable);
  }

  private Thread synchronousPrefetch(final ApolloPrefetch prefetch) {
    Runnable runnable = new Runnable() {
      @Override public void run() {
        try {
          prefetch.execute();
        } catch (ApolloException expected) {

        }
      }
    };
    return startThread(runnable);
  }

  private Thread startThread(Runnable runnable) {
    Thread thread = new Thread(runnable);
    thread.start();
    return thread;
  }

  private MockResponse createMockResponse() {
    return new MockResponse().setResponseCode(200).setBody(new StringBuilder()
        .append("{")
        .append("  \"errors\": [")
        .append("    {")
        .append("      \"message\": \"Cannot query field \\\"names\\\" on type \\\"Species\\\".\",")
        .append("      \"locations\": [")
        .append("        {")
        .append("          \"line\": 3,")
        .append("          \"column\": 5")
        .append("        }")
        .append("      ]")
        .append("    }")
        .append("  ]")
        .append("}")
        .toString());
  }
}