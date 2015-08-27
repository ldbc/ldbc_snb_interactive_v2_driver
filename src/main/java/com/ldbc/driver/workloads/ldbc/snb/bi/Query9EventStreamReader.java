package com.ldbc.driver.workloads.ldbc.snb.bi;


import com.ldbc.driver.Operation;
import com.ldbc.driver.WorkloadException;
import com.ldbc.driver.csv.charseeker.CharSeeker;
import com.ldbc.driver.csv.charseeker.CharSeekerParams;
import com.ldbc.driver.csv.charseeker.Extractors;
import com.ldbc.driver.csv.charseeker.Mark;
import com.ldbc.driver.generator.CsvEventStreamReaderBasicCharSeeker;
import com.ldbc.driver.generator.GeneratorFactory;

import java.io.File;
import java.io.IOException;

public class Query9EventStreamReader extends BaseEventStreamReader
{
    public Query9EventStreamReader(
            File parametersFile,
            CharSeekerParams charSeekerParams,
            GeneratorFactory gf ) throws WorkloadException
    {
        super( parametersFile, charSeekerParams, gf );
    }

    @Override
    Operation operationFromParameters( Object[] parameters )
    {
        return new LdbcSnbBiQuery9(
                (String) parameters[0],
                (int) parameters[1]
        );
    }

    @Override
    CsvEventStreamReaderBasicCharSeeker.EventDecoder<Object[]> decoder()
    {
        return new CsvEventStreamReaderBasicCharSeeker.EventDecoder<Object[]>()
        {
            /*
            TagClass
            names
            */
            @Override
            public Object[] decodeEvent( CharSeeker charSeeker, Extractors extractors, int[] columnDelimiters,
                    Mark mark )
                    throws IOException
            {
                String tagClass;
                if ( charSeeker.seek( mark, columnDelimiters ) )
                {
                    tagClass = charSeeker.extract( mark, extractors.string() ).value();
                }
                else
                {
                    // if first column of next row contains nothing it means the file is finished
                    return null;
                }

                return new Object[]{tagClass, LdbcSnbBiQuery9.DEFAULT_LIMIT};
            }
        };
    }

    @Override
    int columnCount()
    {
        return 1;
    }
}
