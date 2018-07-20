import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val requests = 200

class Http2Test {

    private val server = MockWebServer()
    private val localhost = InetAddress.getByName("localhost").canonicalHostName
    private val localhostCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName(localhost)
            .build()

    private val client by lazy {
        val clientCertificates = HandshakeCertificates.Builder()
                .addTrustedCertificate(localhostCertificate.certificate())
                .build()
        val httpClient = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
                .build()

        // this is required in order to send out many requests at once!
        httpClient.dispatcher().maxRequestsPerHost = requests

        httpClient
    }

    @Before
    fun setUp() {
        val serverCertificates = HandshakeCertificates.Builder()
                .heldCertificate(localhostCertificate)
                .build()

        server.useHttps(serverCertificates.sslSocketFactory(), false)

        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `how NOT to use HTTP2`() {
        enqueueDelayedResponseBodies()

        // BAD PRACTICE: DO NOT DO THIS, because with HTTP2 we can send all requests immediately
        val executor = Executors.newFixedThreadPool(6)
        for (i in 1..requests) {
            executor.submit {
                val call = client.newCall(Request.Builder().url(server.url("/")).build())
                val response = call.execute()
                assertEquals(200, response.code())
                assertEquals("not using HTTP2 - use Java 9 or add alpn-boot-X.jar to your bootclasspath",
                        Protocol.HTTP_2, response.protocol())
            }
        }

        executor.shutdown()
        assertTrue(executor.awaitTermination(1, TimeUnit.MINUTES))
    }

    @Test
    fun `HTTP2 working at its best when using OkHttp threadpool`() {
        enqueueDelayedResponseBodies()

        // request all calls at once and let OkHttp handle the threading
        val countDownLatch = CountDownLatch(requests)
        for (i in 1..requests) {
            val call = client.newCall(Request.Builder().url(server.url("/")).build())
            call.enqueue(object : Callback {

                override fun onResponse(call: Call, response: Response) {
                    assertEquals(200, response.code())
                    assertEquals("not using HTTP2 - use Java 9 or add alpn-boot-X.jar to your bootclasspath",
                            Protocol.HTTP_2, response.protocol())
                    countDownLatch.countDown()
                }

                override fun onFailure(call: Call, e: IOException) {
                    fail(e.toString())
                }

            })
        }

        assertTrue(countDownLatch.await(1, TimeUnit.MINUTES))
    }

    private fun enqueueDelayedResponseBodies() {
        val payload = "$".repeat(1024)
        for (i in 1..requests) {
            server.enqueue(MockResponse().setBody(payload).setHeadersDelay(300, TimeUnit.MILLISECONDS))
        }
    }

}
