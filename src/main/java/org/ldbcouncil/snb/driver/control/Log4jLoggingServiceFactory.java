package org.ldbcouncil.snb.driver.control;

import org.ldbcouncil.snb.driver.temporal.TemporalUtil;

public class Log4jLoggingServiceFactory implements LoggingServiceFactory
{
    private final TemporalUtil temporalUtil;
    private final boolean detailedStatus;

    public Log4jLoggingServiceFactory( boolean detailedStatus )
    {
        this.temporalUtil = new TemporalUtil();
        this.detailedStatus = detailedStatus;
    }

    public LoggingService loggingServiceFor( String source )
    {
        return new Log4jLoggingService( source, temporalUtil, detailedStatus );
    }
}
