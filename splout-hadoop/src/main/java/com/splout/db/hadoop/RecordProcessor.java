package com.splout.db.hadoop;

/*
 * #%L
 * Splout SQL Hadoop library
 * %%
 * Copyright (C) 2012 Datasalt Systems S.L.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.Serializable;

import com.datasalt.pangool.io.ITuple;
import com.splout.db.common.PartitionMap;

/**
 * A custom Java business logic piece that can be implemented by the user and passed to a {@link TableInput}.
 * Here we can implement a custom filter or processor without needing to use the Hadoop/Pangool API directly.
 * <p/>
 * The input Tuple will have the Schema of the file being processed. The returned Tuple must have the same Schema than
 * the Table being created.
 */
@SuppressWarnings("serial")
public abstract class RecordProcessor implements Serializable {

  /**
   * Custom Java business logic can be implemented here.
   * The record contains the parsed fields and can be manipulated.
   * The return can be used to filter out the record if needed (by returning null).
   * So, this function serves as both a filter and a custom record processor.
   * <p/>
   * The input Tuple will have the Schema of the file being processed. The returned Tuple must have the same Schema than
   * the Table being created.
   */
  public abstract ITuple process(ITuple record, CounterInterface context) throws Throwable;
  
  /**
   * Optionally, determine the partition key in a custom way.
   * Return NO_PARTITION if decision can be handled to Splout API.
   */
  public int getPartition(String partitionKey, ITuple record, CounterInterface context) {
    return PartitionMap.NO_PARTITION;
  }
}
