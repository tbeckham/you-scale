/*
 * Copyright 2015 Steve Jones. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.github.sjones4.scale

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.internal.StaticCredentialsProvider
import com.amazonaws.services.identitymanagement.model.DeleteServerCertificateRequest
import com.amazonaws.services.identitymanagement.model.UploadServerCertificateRequest
import com.github.sjones4.youcan.youare.YouAreClient
import org.junit.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.Assert.assertNotNull

/**
 * Scale test for IAM server certificates in a single account.
 */
class ScaleIAMServerCertificatesBasicTest {

  private final AWSCredentialsProvider eucalyptusCredentials

  private final String certPem = '''\
            -----BEGIN CERTIFICATE-----
            MIIDDDCCAfSgAwIBAgIQM6YEf7FVYx/tZyEXgVComTANBgkqhkiG9w0BAQUFADAw
            MQ4wDAYDVQQKDAVPQVNJUzEeMBwGA1UEAwwVT0FTSVMgSW50ZXJvcCBUZXN0IENB
            MB4XDTA1MDMxOTAwMDAwMFoXDTE4MDMxOTIzNTk1OVowQjEOMAwGA1UECgwFT0FT
            SVMxIDAeBgNVBAsMF09BU0lTIEludGVyb3AgVGVzdCBDZXJ0MQ4wDAYDVQQDDAVB
            bGljZTCBnzANBgkqhkiG9w0BAQEFAAOBjQAwgYkCgYEAoqi99By1VYo0aHrkKCNT
            4DkIgPL/SgahbeKdGhrbu3K2XG7arfD9tqIBIKMfrX4Gp90NJa85AV1yiNsEyvq+
            mUnMpNcKnLXLOjkTmMCqDYbbkehJlXPnaWLzve+mW0pJdPxtf3rbD4PS/cBQIvtp
            jmrDAU8VsZKT8DN5Kyz+EZsCAwEAAaOBkzCBkDAJBgNVHRMEAjAAMDMGA1UdHwQs
            MCowKKImhiRodHRwOi8vaW50ZXJvcC5iYnRlc3QubmV0L2NybC9jYS5jcmwwDgYD
            VR0PAQH/BAQDAgSwMB0GA1UdDgQWBBQK4l0TUHZ1QV3V2QtlLNDm+PoxiDAfBgNV
            HSMEGDAWgBTAnSj8wes1oR3WqqqgHBpNwkkPDzANBgkqhkiG9w0BAQUFAAOCAQEA
            BTqpOpvW+6yrLXyUlP2xJbEkohXHI5OWwKWleOb9hlkhWntUalfcFOJAgUyH30TT
            pHldzx1+vK2LPzhoUFKYHE1IyQvokBN2JjFO64BQukCKnZhldLRPxGhfkTdxQgdf
            5rCK/wh3xVsZCNTfuMNmlAM6lOAg8QduDah3WFZpEA0s2nwQaCNQTNMjJC8tav1C
            Br6+E5FAmwPXP7pJxn9Fw9OXRyqbRA4v2y7YpbGkG2GI9UvOHw6SGvf4FRSthMMO
            35YbpikGsLix3vAsXWWi4rwfVOYzQK0OFPNi9RMCUdSH06m9uLWckiCxjos0FQOD
            ZE9l4ATGy9s9hNVwryOJTw==
            -----END CERTIFICATE-----'''.stripIndent( )

  private final String keyPem = '''\
            -----BEGIN RSA PRIVATE KEY-----
            MIICXAIBAAKBgQCiqL30HLVVijRoeuQoI1PgOQiA8v9KBqFt4p0aGtu7crZcbtqt
            8P22ogEgox+tfgan3Q0lrzkBXXKI2wTK+r6ZScyk1wqctcs6OROYwKoNhtuR6EmV
            c+dpYvO976ZbSkl0/G1/etsPg9L9wFAi+2mOasMBTxWxkpPwM3krLP4RmwIDAQAB
            AoGAY+fazB357rE1YVrh2hlgwh6lr3YRASmzaye+MLOAdNCPW5Sm8iFL5Cn7IU2v
            /kKi2eW21oeaLtFzsMU9W2LJP6h33caPcMr/1F3wsiHRCBSZiRLgroYnryJ2pWRq
            B8r6/j1mCKzNkoxwspUS1tPFIT0yJB4L/bQGMIvnoM4v5aECQQDX2hBKRbsQYSgL
            xZmqx/KJG7+rcpjYXBcztcO09sAsJ+tJe7FPKoKB1CG/KWqj8KQn69blXxhKRDTp
            rPZLiU7RAkEAwOnfR+dwLbnNGTuafvvbWE1d0CCa3YGooCrrCq4Af7D5jv9TZXDx
            yOIZsHzQH5U47e9ht2JvYllbTlMhirKsqwJBAKbyAadwRz5j5pU0P6XW/78LtzLj
            b1Pn5goYi0VrkzaTqWcsQ/b26fmAGJnBbrldZZl6zrqY0jCekE4reFLz4AECQA7Y
            MEFFMuGh4YFmj73jvX4u/eANEj2nQ4WHp+x7dTheMuXpCc7NgR13IIjvIci8X9QX
            Toqg/Xcw7xC43uTgWN8CQF2p4WulNa6U64sxyK1gBWOr6kwx6PWU29Ay6MPDPAJP
            O84lDgb5dlC1SGE+xHUzPPN6E4YFI/ECawOHNrADEsE=
            -----END RSA PRIVATE KEY-----'''.stripIndent( )

  ScaleIAMServerCertificatesBasicTest( ) {
    this.eucalyptusCredentials = new StaticCredentialsProvider( new BasicAWSCredentials(
        Objects.toString( System.getenv('AWS_ACCESS_KEY_ID'),     System.getenv('AWS_ACCESS_KEY') ),
        Objects.toString( System.getenv('AWS_SECRET_ACCESS_KEY'), System.getenv('AWS_SECRET_KEY') )
    ) )
  }

  private String cloudUri( String env, String servicePath ) {
    String url = System.getenv( env )
    assertNotNull( "Expected URL from environment (${env})", url )
    URI.create( url )
        .resolve( servicePath )
        .toString()
  }

  private YouAreClient getYouAreClient( final AWSCredentialsProvider credentials = eucalyptusCredentials ) {
    final YouAreClient euare = new YouAreClient( credentials, new ClientConfiguration(
        socketTimeout: TimeUnit.MINUTES.toMillis( 2 )
    ) )
    euare.setEndpoint( cloudUri( 'AWS_IAM_URL', '/services/Euare' ) )
    euare
  }

  private void print( String text ) {
    System.out.println( text )
  }

  @Test
  void test( ) {
    final String namePrefix = UUID.randomUUID().toString().substring(0, 13) + "-";
    print( "Using resource prefix for test: " + namePrefix );

    final long startTime = System.currentTimeMillis( )
    final List<List<Runnable>> allCleanupTasks = new ArrayList<>( )
    try {
      final int threads = 20
      final int serverCertificates = 10000
      final int iterations = serverCertificates / threads
      print( "Creating ${serverCertificates} server certificates using ${threads} threads" )
      final CountDownLatch latch = new CountDownLatch( threads )
      ( 1..threads ).each { Integer thread ->
        final List<Runnable> cleanupTasks = [] as List<Runnable>
        allCleanupTasks << cleanupTasks
        Thread.start {
          try {
            getYouAreClient( ).with {
              ( 1..iterations ).each{ Integer serverCertificate ->
                final String serverCertificateName = "${namePrefix}server-certificate-${thread}-${serverCertificate}"
                uploadServerCertificate( new UploadServerCertificateRequest(
                    serverCertificateName: serverCertificateName,
                    certificateBody: certPem,
                    privateKey: keyPem
                ) )
                cleanupTasks.add {
                  deleteServerCertificate(new DeleteServerCertificateRequest(
                      serverCertificateName: serverCertificateName
                  ))
                }
                if (serverCertificate % 100 == 0) {
                  println("[${thread}] Created ${serverCertificate} server certificates")
                }
              }
            }
          } finally {
            latch.countDown( )
          }
        }
      }
      latch.await( )

      print( "Created ${serverCertificates} server certificates, describing." )
      long before = System.currentTimeMillis( )
      getYouAreClient( ).with {
        listServerCertificates( ).with {
          print( "Described ${serverCertificateMetadataList.size()} server certificates" )
        }
      }
      print( "Described server certificates in ${System.currentTimeMillis()-before}ms" )

      print( "Test complete in ${System.currentTimeMillis()-startTime}ms" )
    } finally {
      // Attempt to clean up anything we created
      print( "Running cleanup tasks" )
      final long cleanupStart = System.currentTimeMillis( )
      final CountDownLatch cleanupLatch = new CountDownLatch( allCleanupTasks.size( ) )
      allCleanupTasks.each { List<Runnable> cleanupTasks ->
        Thread.start {
          try {
            cleanupTasks.reverseEach { Runnable cleanupTask ->
              try {
                cleanupTask.run()
              } catch ( Exception e ) {
                e.printStackTrace( )
              }
            }
          } finally {
            cleanupLatch.countDown( )
          }
        }
      }
      cleanupLatch.await( )
      print( "Completed cleanup tasks in ${System.currentTimeMillis()-cleanupStart}ms" )
    }
  }
}
