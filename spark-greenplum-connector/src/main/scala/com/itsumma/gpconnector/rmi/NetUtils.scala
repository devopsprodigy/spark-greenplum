package com.itsumma.gpconnector.rmi

import org.apache.spark.internal.Logging
import org.apache.commons.lang3.{SystemUtils}

import java.net.{DatagramSocket, Inet4Address, InetAddress, InterfaceAddress, NetworkInterface, SocketException}
import scala.collection.JavaConverters._

case class NetUtils() extends Logging {

  /**
   * Waits until condition become true or msMax milliseconds elapsed
   * @param msMax
   * @param fun lambda function returning Boolean
   * @return false if timeout elapsed or true otherwise
   */
  def waitForCompletion(msMax: Long = 60000)(fun: => Boolean): Boolean = {
    val start = System.currentTimeMillis()
    val rnd = new scala.util.Random
    var result: Boolean = false
    while (!result &&
      !Thread.currentThread().isInterrupted &&
      (((System.currentTimeMillis() - start) < msMax) || (msMax == -1))) {
      result = fun
      if (!result)
        Thread.sleep(rnd.nextInt(10) + 1)
    }
    result
  }

  /**
   * Get tuple of hostName, ipAddress associated with the local host,
   * choosing the default route network interface if possible.
   *
   * Slightly modified version of <a href="https://github.com/apache/spark/blob/v3.5.4/core/src/main/scala/org/apache/spark/util/Utils.scala#L952">org.apache.spark.util.Utils.findLocalInetAddress</a>
   *
   * @return (hostName, ipAddress)
   */
  def getLocalHostNameAndIp: (String, String) = {
    var address = InetAddress.getLocalHost
    if (address.isLoopbackAddress) {
        // Address resolves to something like 127.0.1.1, which happens on Debian; try to find
        // a better address using the local network interfaces
        // getNetworkInterfaces returns ifs in reverse order compared to ifconfig output order
        // on unix-like system. On windows, it returns in index order.
        // It's more proper to pick ip address following system output order.
        val activeNetworkIFs = NetworkInterface.getNetworkInterfaces.asScala.toSeq
        val reOrderedNetworkIFs = if (SystemUtils.IS_OS_WINDOWS) activeNetworkIFs else activeNetworkIFs.reverse

        for (ni <- reOrderedNetworkIFs) {
            val addresses = ni.getInetAddresses.asScala
              .filterNot(addr => addr.isLinkLocalAddress || addr.isLoopbackAddress).toSeq
            if (addresses.nonEmpty) {
                val addr = addresses.find(_.isInstanceOf[Inet4Address]).getOrElse(addresses.head)
                // because of Inet6Address.toHostName may add interface at the end if it knows about it
                val strippedAddress = InetAddress.getByAddress(addr.getAddress)
                // We've found an address that looks reasonable!
                logWarning(s"Your hostname, ${address.getHostName}, " +
                    s"resolves to a loopback address: ${address.getHostAddress}; " +
                    s"using ${strippedAddress.getHostAddress} instead (on interface " +
                    s"${ni.getName})")
                return (address.getCanonicalHostName, strippedAddress.getHostAddress)
            }
        }
        logWarning(s"Your hostname, ${address.getHostName}, " +
            s"resolves to a loopback address: ${address.getHostAddress}, " +
            s"but we couldn't find any external IP address!")
    }

    (address.getCanonicalHostName, address.getHostAddress)
  }

  /**
   * Resolves given host name to IP address.
   * In the case of local host name tries to avoid loopback address and choose
   * IP associated with some other interface of same host if possible.
   *
   * @param hostName
   * @return IP as string
   */
  def resolveHost2Ip(hostName: String): String = {
    var isLocal: Boolean = false
    for (address <- InetAddress.getAllByName(hostName)) {
      if (!address.isLoopbackAddress) {
        val ip = address.getHostAddress
        logInfo(s"Resolved ${hostName} to ${ip}")
        return ip
      }
      if (address.isLoopbackAddress || address.isSiteLocalAddress) {
        isLocal = true
      }
    }
    if (isLocal) {
      val (name, ip) = getLocalHostNameAndIp
      logInfo(s"Resolved ${hostName} to local ${ip}/${name}")
      return ip
    }
    logInfo(s"Unable to resolve ${hostName}")
    null
  }
}
