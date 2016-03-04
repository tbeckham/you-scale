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
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult
import com.amazonaws.services.ec2.model.DescribeImagesRequest
import com.amazonaws.services.ec2.model.DescribeInstancesRequest
import com.amazonaws.services.ec2.model.Filter
import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.Placement
import com.amazonaws.services.ec2.model.RunInstancesRequest
import com.amazonaws.services.ec2.model.TerminateInstancesRequest
import org.junit.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertTrue

/**
 * Scale test for EC2 instances in a single account.
 */
class ScaleEC2InstancesBasicTest {

  private final AWSCredentialsProvider eucalyptusCredentials

  ScaleEC2InstancesBasicTest( ) {
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

  private AmazonEC2 getEC2Client( final AWSCredentialsProvider credentials = eucalyptusCredentials ) {
    final AmazonEC2 ec2 = new AmazonEC2Client( credentials, new ClientConfiguration(
        socketTimeout: TimeUnit.MINUTES.toMillis( 2 )
    ) )
    ec2.setEndpoint( cloudUri( 'EC2_URL', '/services/compute' ) )
    ec2
  }

  private void print( String text ) {
    System.out.println( text )
  }

  @Test
  void test( ) {
    final AmazonEC2 ec2 = getEC2Client( )

    // Find an AZ to use
    final DescribeAvailabilityZonesResult azResult = ec2.describeAvailabilityZones();

    assertTrue( 'Availability zone not found', azResult.getAvailabilityZones().size() > 0 );

    final String availabilityZone = azResult.getAvailabilityZones().get( 0 ).getZoneName();
    print( "Using availability zone: " + availabilityZone );

    // Find an image to use
    final String imageId = ec2.describeImages( new DescribeImagesRequest(
        filters: [
            new Filter( name: "image-type", values: ["machine"] ),
            new Filter( name: "root-device-type", values: ["instance-store"] ),
            new Filter( name: "is-public", values: ["true"] ),
        ]
    ) ).with {
      images?.getAt( 0 )?.imageId
    }
    assertNotNull( 'Image not found', imageId != null )
    print( "Using image: ${imageId}" )

    // Find a key pair
    final String key = ec2.describeKeyPairs( ).with {
      keyPairs?.getAt( 0 )?.keyName
    }
    print( "Using key: ${key}" )
    
    final String namePrefix = UUID.randomUUID().toString().substring(0, 13) + "-";
    print( "Using resource prefix for test: " + namePrefix );

    final long startTime = System.currentTimeMillis( )
    final List<List<Runnable>> allCleanupTasks = new ArrayList<>( )
    try {
      final int target = 2000
      final int threads = 20
      final int iterations = target / threads
      print( "Creating ${target} instances using ${threads} threads" )
      final CountDownLatch latch = new CountDownLatch( threads )
      ( 1..threads ).each { Integer thread ->
        final List<Runnable> cleanupTasks = [] as List<Runnable>
        allCleanupTasks << cleanupTasks
        Thread.start {
          getEC2Client( ).with {
            try {
              (1..iterations).each { Integer count ->
                runInstances( new RunInstancesRequest(
                    imageId: imageId,
                    instanceType: 't1.micro',
                    placement: new Placement(
                        availabilityZone: availabilityZone
                    ),
                    keyName: key,
                    minCount: 1,
                    maxCount: 1,
                    clientToken: "${namePrefix}${thread}-${count}"
                ) ).with {
                  reservation?.instances?.each{ Instance instance ->
                    cleanupTasks.add{
                      terminateInstances( new TerminateInstancesRequest(
                        instanceIds: [ instance.instanceId ]
                      ) )
                    }
                  }
                }
                if (count % 100 == 0) {
                  println("[${thread}] Launched ${count} instances")
                }
              }
            } finally {
              latch.countDown( )
            }
          }
        }
      }
      latch.await( )

      print( "Launched ${target} instances, describing." )
      long before = System.currentTimeMillis( )
      getEC2Client( ).with {
        describeInstances( new DescribeInstancesRequest( 
          filters: [
              new Filter( name: 'instance-state-name', values: [ 'pending', 'running' ] ),
              new Filter( name: 'client-token', values: [ "${namePrefix}*" as String ] ),
          ]
        ) ).with {
          print( "Described ${reservations.size()} instances" )
        }
      }
      print( "Described instances in ${System.currentTimeMillis()-before}ms" )

      ( 1..125 ).find{ Integer index ->
        sleep( 10000 )
        print( "Describing instance status ${index}" )
        long beforeIS = System.currentTimeMillis( )
        Integer running = getEC2Client( ).with {
          describeInstanceStatus( ).with {
            print( "Described ${instanceStatuses.size()} instance status" )
            instanceStatuses.size( )
          }
        }
        print( "Described instance status in ${System.currentTimeMillis()-beforeIS}ms" )
        running >= target ? running : null
      }

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
