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
import com.amazonaws.Request
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.handlers.AbstractRequestHandler
import com.amazonaws.internal.StaticCredentialsProvider
import com.amazonaws.services.identitymanagement.model.AccessKeyMetadata
import com.amazonaws.services.identitymanagement.model.CreateAccessKeyRequest
import com.amazonaws.services.identitymanagement.model.CreateUserRequest
import com.amazonaws.services.identitymanagement.model.DeleteAccessKeyRequest
import com.amazonaws.services.identitymanagement.model.DeleteUserRequest
import com.amazonaws.services.identitymanagement.model.ListAccessKeysRequest
import com.amazonaws.services.identitymanagement.model.ListAccessKeysResult
import com.amazonaws.services.identitymanagement.model.ListUsersResult
import com.amazonaws.services.identitymanagement.model.User
import com.github.sjones4.youcan.youare.YouAreClient
import com.github.sjones4.youcan.youare.model.CreateAccountRequest
import com.github.sjones4.youcan.youare.model.DeleteAccountRequest
import org.junit.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.Assert.assertNotNull

/**
 * Scale test for IAM access keys.
 *
 * https://eucalyptus.atlassian.net/browse/EUCA-11091
 */
class ScaleIAMAccessKeysTest {

  private final AWSCredentialsProvider eucalyptusCredentials

  ScaleIAMAccessKeysTest( ) {
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
      final int threads = 50
      final int accounts = 500
      final int users = 5000
      final int keys = 2
      final int iterations = accounts / threads
      print( "Creating ${users} users with ${keys} access keys in ${accounts} accounts using ${threads} threads" )
      final CountDownLatch latch = new CountDownLatch( threads )
      ( 1..threads ).each { Integer thread ->
        final List<Runnable> cleanupTasks = [] as List<Runnable>
        allCleanupTasks << cleanupTasks
        Thread.start {
          try{
            ( 1..iterations ).each { Integer account ->
              final String accountName = "${namePrefix}account-${thread}-${account}"
              getYouAreClient( ).with {
                // Create account for testing
                print("[${thread}] Creating account ${accountName}")
                createAccount(new CreateAccountRequest(accountName: accountName))
                cleanupTasks.add {
                  print("Deleting account: ${accountName}")
                  deleteAccount(new DeleteAccountRequest(accountName: accountName, recursive: true))
                }
              }

              // Get credentials for new account
              AWSCredentialsProvider accountCredentials = getYouAreClient().with {
                addRequestHandler(new AbstractRequestHandler() {
                  public void beforeRequest(final Request<?> request) {
                    request.addParameter("DelegateAccount", accountName)
                  }
                })
                createAccessKey(new CreateAccessKeyRequest(userName: "admin")).with {
                  accessKey?.with {
                    new StaticCredentialsProvider(new BasicAWSCredentials(accessKeyId, secretAccessKey))
                  }
                }
              }
              assertNotNull("[${thread}] Expected account credentials", accountCredentials)

              getYouAreClient( accountCredentials ).with {
                cleanupTasks.add {
                  print("Deleting users for account: ${accountName}")
                  listUsers( ).with { ListUsersResult result ->
                    result.users.each { User user ->
                      if ( user.userName != 'admin' ) {
                        listAccessKeys( new ListAccessKeysRequest( userName: user.userName ) ).with {
                          ListAccessKeysResult keysResult ->
                            keysResult.accessKeyMetadata.each { AccessKeyMetadata key ->
                              deleteAccessKey( new DeleteAccessKeyRequest(
                                  userName: user.userName,
                                  accessKeyId: key.accessKeyId
                              ) )
                            }
                        }
                        deleteUser( new DeleteUserRequest( userName: user.userName ) )
                      }
                    }
                  }
                }

                print( "[${thread}] Creating ${users} users with ${keys} access keys for account ${accountName}" )
                (1..users).each { Integer user ->
                  final String userName = "${namePrefix}user-${thread}-${user}"
                  createUser( new CreateUserRequest( userName: userName ) )
                  (1..keys).each { Integer key ->
                    createAccessKey( new CreateAccessKeyRequest( userName: userName ) )
                  }
                  if (user % 1000 == 0) {
                    println("[${thread}] Created ${user} users")
                  }
                }
              }
            }
          } finally {
            latch.countDown( )
          }
        }
      }
      latch.await( )

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
