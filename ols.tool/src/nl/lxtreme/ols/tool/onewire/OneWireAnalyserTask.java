/*
 * OpenBench LogicSniffer / SUMP project 
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110, USA
 *
 * 
 * Copyright (C) 2010-2011 - J.W. Janssen, http://www.lxtreme.nl
 */
package nl.lxtreme.ols.tool.onewire;


import static nl.lxtreme.ols.common.annotation.DataAnnotation.*;
import static nl.lxtreme.ols.tool.base.NumberUtils.*;

import java.util.concurrent.*;
import java.util.logging.*;

import aQute.bnd.annotation.metatype.*;

import nl.lxtreme.ols.common.*;
import nl.lxtreme.ols.common.acquisition.*;
import nl.lxtreme.ols.common.util.*;
import nl.lxtreme.ols.tool.api.*;


/**
 * Provides a 1-wire decoder.
 */
public class OneWireAnalyserTask implements Callable<Void>
{
  // CONSTANTS

  static final String EVENT_RESET = "RESET";
  static final String EVENT_BUS_ERROR = "BUS-ERROR";
  static final String KEY_SLAVE_PRESENT = "slavePresent";

  private static final String OW_1_WIRE = "1-Wire";

  private static final Logger LOG = Logger.getLogger( OneWireAnalyserTask.class.getName() );

  // VARIABLES

  private final ToolContext context;
  private final ToolProgressListener progressListener;

  private final int owLineIndex;
  private final int owLineMask;
  private final OneWireTiming owTiming;

  // CONSTRUCTORS

  /**
   * Creates a new OneWireAnalyserWorker instance.
   * 
   * @param aContext
   * @param aProgressListener
   *          the progress listener to use for reporting the progress, cannot be
   *          <code>null</code>.
   */
  public OneWireAnalyserTask( final ToolContext aContext, final Configuration aConfiguration )
  {
    this.context = aContext;
    this.progressListener = aContext.getProgressListener();

    OneWireConfig config = Configurable.createConfigurable( OneWireConfig.class, aConfiguration.asMap() );

    this.owLineIndex = config.channelIdx();
    this.owLineMask = 1 << this.owLineIndex;

    this.owTiming = new OneWireTiming( config.busMode() );
  }

  // METHODS

  /**
   * {@inheritDoc}
   */
  @Override
  public Void call() throws ToolException
  {
    final AcquisitionData data = this.context.getAcquisitionData();
    final ToolAnnotationHelper annotationHelper = new ToolAnnotationHelper( this.context );
    final int[] values = data.getValues();

    int sampleIdx;

    final int dataMask = this.owLineMask;
    final int sampleCount = values.length;

    if ( LOG.isLoggable( Level.FINE ) )
    {
      LOG.log( Level.FINE, "1-Wire Line mask = 0x{0}", Integer.toHexString( this.owLineMask ) );
    }

    // Search the moment on which the 1-wire line is idle (= high)...
    for ( sampleIdx = 0; sampleIdx < sampleCount; sampleIdx++ )
    {
      final int dataValue = values[sampleIdx];

      if ( ( dataValue & dataMask ) == dataMask )
      {
        // IDLE found here
        break;
      }
    }

    if ( sampleIdx == sampleCount )
    {
      // no idle state could be found
      LOG.log( Level.WARNING, "No IDLE state found in data; aborting analysis..." );
      throw new ToolException( "No IDLE state found!" );
    }

    // Update the channel label and clear any existing annotations on the
    // channel...
    annotationHelper.prepareChannel( this.owLineIndex, OW_1_WIRE );
    // Decode the actual data...
    decodeData( annotationHelper, data, sampleIdx, sampleCount - 1 );

    return null;
  }

  /**
   * Does the actual decoding of the 1-wire data.
   * 
   * @param aAnnotationHelper
   * @param aDataSet
   *          the decoded data set to add the decoding results to, cannot be
   *          <code>null</code>.
   */
  private void decodeData( final ToolAnnotationHelper aAnnotationHelper, final AcquisitionData aData, final int aStartIdx,
      final int aEndIdx )
  {
    final long[] timestamps = aData.getTimestamps();

    this.progressListener.setProgress( 0 );

    final long startOfDecode = timestamps[aStartIdx];
    final long endOfDecode = timestamps[aEndIdx];

    // The timing of the 1-wire bus is done in uS, so determine what scale we've
    // to use in order to obtain those kind of time values...
    final double timingCorrection = ( 1.0e6 / aData.getSampleRate() );

    long time = Math.max( 0, startOfDecode );

    int bitCount = 8;
    int byteValue = 0;
    long byteStartTime = time;

    while ( ( endOfDecode - time ) > 0 )
    {
      long fallingEdge = findEdge( aData, time, endOfDecode, Edge.FALLING );
      if ( fallingEdge < 0 )
      {
        LOG.log( Level.INFO, "Decoding ended at {0}; no falling edge found...",
            UnitOfTime.format( time / ( double )aData.getSampleRate() ) );
        break;
      }
      long risingEdge = findEdge( aData, fallingEdge, endOfDecode, Edge.RISING );
      if ( risingEdge < 0 )
      {
        risingEdge = endOfDecode;
      }

      // Take the difference in time, which should be an indication of what
      // symbol is transmitted...
      final double diff = ( ( risingEdge - fallingEdge ) * timingCorrection );
      if ( this.owTiming.isReset( diff ) )
      {
        // Reset pulse...
        time = ( long )( fallingEdge + ( this.owTiming.getResetFrameLength() / timingCorrection ) );

        // Check for the existence of a "slave present" symbol...
        final boolean slavePresent = isSlavePresent( aData, fallingEdge, time, timingCorrection );
        LOG.log( Level.FINE, "Master bus reset; slave is {0}present...", ( slavePresent ? "" : "NOT " ) );

        final String desc = String.format( "Master reset, slave %s present", slavePresent ? "is" : "is NOT" );

        aAnnotationHelper.addEventAnnotation( this.owLineIndex, fallingEdge, time, EVENT_RESET, KEY_COLOR, "#e0e0e0",
            KEY_DESCRIPTION, desc, KEY_SLAVE_PRESENT, Boolean.valueOf( slavePresent ) );
      }
      else
      {
        if ( bitCount == 8 )
        {
          // Take the falling edge of the most significant bit as start of our
          // decoded byte value...
          byteStartTime = fallingEdge;
        }

        if ( this.owTiming.isZero( diff ) )
        {
          // Zero bit: only update timing...
          time = ( long )( fallingEdge + ( this.owTiming.getBitFrameLength() / timingCorrection ) );
        }
        else if ( this.owTiming.isOne( diff ) )
        {
          // Bytes are sent LSB first, so decode the byte as well with LSB
          // first...
          byteValue |= 0x80;
          time = ( long )( fallingEdge + ( this.owTiming.getBitFrameLength() / timingCorrection ) );
        }
        else
        {
          // Unknown symbol; report it as bus error and restart our byte...
          time = ( long )( fallingEdge + ( this.owTiming.getBitFrameLength() / timingCorrection ) );

          aAnnotationHelper.addErrorAnnotation( this.owLineIndex, byteStartTime, time, EVENT_BUS_ERROR, KEY_COLOR,
              "#ff8000", KEY_DESCRIPTION, "Timing issue." );

          byteValue = 0;
          bitCount = 8;
          time = fallingEdge;
          // Don't bother continuing; instead start over...
          continue;
        }

        if ( --bitCount == 0 )
        {
          // Report the complete byte value...
          aAnnotationHelper.addSymbolAnnotation( this.owLineIndex, byteStartTime, time, byteValue );

          byteValue = 0;
          bitCount = 8;
        }
        else
        {
          byteValue >>= 1;
        }
      }

      // Update progress...
      this.progressListener.setProgress( getPercentage( time, startOfDecode, endOfDecode ) );
    }

    this.progressListener.setProgress( 100 );
  }

  /**
   * Find first falling edge this is the start of the start bit. If the signal
   * is inverted, find the first rising edge.
   * 
   * @param aStartOfDecode
   *          the timestamp to start searching;
   * @param aEndOfDecode
   *          the timestamp to end the search;
   * @param aMask
   *          the bit-value mask to apply for finding the start bit.
   * @return the time at which the start bit was found, -1 if it is not found.
   */
  private long findEdge( final AcquisitionData aData, final long aStartOfDecode, final long aEndOfDecode,
      final Edge aEdge )
  {
    long result = -1;

    int oldBitValue = getDataValue( aData, aStartOfDecode ) & this.owLineMask;
    for ( long timeCursor = aStartOfDecode + 1; ( result < 0 ) && ( timeCursor < aEndOfDecode ); timeCursor++ )
    {
      final int bitValue = getDataValue( aData, timeCursor ) & this.owLineMask;

      final Edge edge = Edge.toEdge( oldBitValue, bitValue );
      if ( aEdge == edge )
      {
        result = timeCursor;
      }

      oldBitValue = bitValue;
    }

    return result;
  }

  /**
   * Returns the data value for the given time stamp.
   * 
   * @param aTimeValue
   *          the time stamp to return the data value for.
   * @return the data value of the sample index right before the given time
   *         value.
   */
  private int getDataValue( final AcquisitionData aData, final long aTimeValue )
  {
    final int[] values = aData.getValues();
    final long[] timestamps = aData.getTimestamps();

    int i;
    for ( i = 1; i < timestamps.length; i++ )
    {
      if ( aTimeValue < timestamps[i] )
      {
        break;
      }
    }
    return values[i - 1];
  }

  /**
   * Returns whether between the given timestamps a slave presence pulse was
   * found.
   * <p>
   * A slave presence pulse is defined as: take the difference in time between
   * the first rising and falling edge between the given timestamps, if this
   * difference is beyond a certain threshold this pulse can be considered a
   * slave presence pulse.
   * </p>
   * 
   * @param aStart
   *          the start timestamp;
   * @param aEnd
   *          the end timestamp;
   * @param aMask
   *          the line mask;
   * @param aTimingCorrection
   *          the timing correction to correct the timestamps to microseconds.
   * @return <code>true</code> if a slave presence pulse was found,
   *         <code>false</code> otherwise.
   */
  private boolean isSlavePresent( final AcquisitionData aData, final long aStart, final long aEnd,
      final double aTimingCorrection )
  {
    final long risingEdgeTimestamp = findEdge( aData, aStart, aEnd, Edge.RISING );
    if ( risingEdgeTimestamp < 0 )
    {
      return false;
    }

    final long fallingEdgeTimestamp = findEdge( aData, risingEdgeTimestamp, aEnd, Edge.FALLING );
    if ( fallingEdgeTimestamp < 0 )
    {
      return false;
    }

    return this.owTiming.isSlavePresencePulse( ( fallingEdgeTimestamp - risingEdgeTimestamp ) * aTimingCorrection );
  }
}