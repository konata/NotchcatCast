package notch.cat.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaSoapTest {
  @Test
  fun actionPrefersSoapActionHeaderAndFallsBackToBody() {
    assertEquals("Play", DlnaSoap.action(mapOf("soapaction" to "\"urn:schemas-upnp-org:service:AVTransport:1#Play\""), ""))
    assertEquals("SetVolume", DlnaSoap.action(emptyMap(), """<s:Body><u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"/></s:Body>"""))
  }

  @Test
  fun argUnescapesNamespacedSoapValues() {
    val body = """<u:SetAVTransportURI><CurrentURI>https://a.test/watch?a=1&amp;b=2</CurrentURI><dc:Title>Ignored</dc:Title></u:SetAVTransportURI>"""

    assertEquals("https://a.test/watch?a=1&b=2", DlnaSoap.arg(body, "CurrentURI"))
    assertEquals("", DlnaSoap.arg(body, "Missing"))
  }

  @Test
  fun okAndFaultBodiesEscapeXmlValues() {
    val ok = DlnaSoap.ok("RenderingControl", "GetVolume", mapOf("CurrentVolume" to "7<&"))
    val fault = DlnaSoap.faultBody(714, "Illegal <type>")

    assertTrue(ok.contains("<CurrentVolume>7&lt;&amp;</CurrentVolume>"))
    assertTrue(fault.contains("<errorCode>714</errorCode>"))
    assertTrue(fault.contains("<errorDescription>Illegal &lt;type&gt;</errorDescription>"))
  }

  @Test
  fun timeHelpersUseDlnaClockFormatAndMilliseconds() {
    assertEquals("1:02:03", DlnaSoap.formatTime(3_723_000L))
    assertEquals(3_723_000L, DlnaSoap.parseTime("1:02:03.456"))
    assertEquals(62_000L, DlnaSoap.parseTime("1:02"))
    assertEquals(7_000L, DlnaSoap.parseTime("7"))
    assertEquals(0L, DlnaSoap.parseTime(""))
  }
}
