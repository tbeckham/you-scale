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
import com.amazonaws.services.identitymanagement.model.CreateRoleRequest
import com.amazonaws.services.identitymanagement.model.DeleteRoleRequest
import com.amazonaws.services.identitymanagement.model.ListRolesResult
import com.github.sjones4.youcan.youare.YouAreClient
import org.junit.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.Assert.assertNotNull

/**
 * Scale test for IAM roles in a single account.
 */
class ScaleIAMRolesBasicTest {

  private final AWSCredentialsProvider eucalyptusCredentials

  private final String assumeRolePolicyDocument = """\
            {
                "Statement": [ {
                  "Effect": "Allow",
                  "Principal": {
                     "AWS": [ "arn:aws:iam::000000000000:user/admin" ]
                  },
                  "Action": [ "sts:AssumeRole" ]
                } ]
            }
            """.stripIndent() as String

  ScaleIAMRolesBasicTest( ) {
    this.eucalyptusCredentials = new StaticCredentialsProvider( new BasicAWSCredentials(
        System.getenv('AWS_ACCESS_KEY'),
        System.getenv('AWS_SECRET_KEY')
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
    )  )
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
      final int roles = 5000
      final int iterations = roles / threads
      print( "Creating ${roles} roles using ${threads} threads" )
      final CountDownLatch latch = new CountDownLatch( threads )
      ( 1..threads ).each { Integer thread ->
        final List<Runnable> cleanupTasks = [] as List<Runnable>
        allCleanupTasks << cleanupTasks
        Thread.start {
          try {
            getYouAreClient( ).with {
              ( 1..iterations ).each{ Integer role ->
                final String roleName = "${namePrefix}role-${thread}-${role}"
                createRole(new CreateRoleRequest(
                    roleName: roleName,
                    assumeRolePolicyDocument: assumeRolePolicyDocument
                ))
                cleanupTasks.add {
                  deleteRole(new DeleteRoleRequest(roleName: roleName))
                }
                if (role % 100 == 0) {
                  println("[${thread}] Created ${role} roles")
                }
              }
            }
          } finally {
            latch.countDown( )
          }
        }
      }
      latch.await( )

      print( "Created ${roles} roles, describing." )
      long before = System.currentTimeMillis( )
      getYouAreClient( ).with {
        listRoles( ).with { ListRolesResult result ->
          print( "Described ${result.roles.size()} roles" )
        }
      }
      print( "Described roles in ${System.currentTimeMillis()-before}ms" )

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
