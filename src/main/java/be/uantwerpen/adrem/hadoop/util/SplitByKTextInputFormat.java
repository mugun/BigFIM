/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package be.uantwerpen.adrem.hadoop.util;

import static com.google.common.collect.Lists.newArrayList;
import static be.uantwerpen.adrem.util.FIMOptions.NUMBER_OF_LINES_KEY;

import java.io.IOException;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.apache.hadoop.util.LineReader;

/**
 * Input format that splits a file in a number of chunks given by Config.NUMBER_OF_MAPPERS_KEY.
 */
public class SplitByKTextInputFormat extends FileInputFormat<LongWritable,Text> {
  
  public static final String NUMBER_OF_CHUNKS = "number_of_chunks";
  
  @Override
  public RecordReader<LongWritable,Text> createRecordReader(InputSplit genericSplit, TaskAttemptContext context)
      throws IOException {
    context.setStatus(genericSplit.toString());
    return new LineRecordReader();
  }
  
  @Override
  public List<InputSplit> getSplits(JobContext job) throws IOException {
    List<InputSplit> splits = newArrayList();
    int numberOfSplits = getNumberOfSplits(job);
    for (FileStatus status : listStatus(job)) {
      splits.addAll(getSplitsForFile(status, job.getConfiguration(), numberOfSplits));
    }
    return splits;
  }
  
  /**
   * Gets the different file splits for the data based on a given number of splits
   * 
   * @param status
   *          file status
   * @param conf
   *          hadoop configuration object
   * @param numberOfSplits
   *          number of splits to split the data in
   * @return list of file splits
   * @throws IOException
   *           thrown if the file does not exist
   */
  public static List<FileSplit> getSplitsForFile(FileStatus status, Configuration conf, int numberOfSplits)
      throws IOException {
    List<FileSplit> splits = newArrayList();
    Path fileName = status.getPath();
    if (status.isDir()) {
      throw new IOException("Not a file: " + fileName);
    }
    long totalNumberOfLines = getTotalNumberOfLines(conf, fileName);
    int numLinesPerSplit = (int) Math.ceil(1.0 * totalNumberOfLines / numberOfSplits);
    LineReader lr = null;
    FSDataInputStream in = null;
    try {
      in = fileName.getFileSystem(conf).open(fileName);
      lr = new LineReader(in, conf);
      Text line = new Text();
      int numLines = 0;
      long begin = 0;
      long length = 0;
      int num = -1;
      while ((num = lr.readLine(line)) > 0) {
        numLines++;
        length += num;
        if (numLines == numLinesPerSplit) {
          splits.add(createFileSplit(fileName, begin, length));
          begin += length;
          length = 0;
          numLines = 0;
        }
      }
      if (numLines != 0) {
        splits.add(createFileSplit(fileName, begin, length));
      }
    } finally {
      if (lr != null) {
        lr.close();
      }
      if (in != null) {
        in.close();
      }
    }
    return splits;
  }
  
  /**
   * Gets the total number of lines from the file. If Config.NUMBER_OF_LINES_KEY is set, this value is returned.
   * 
   * @param conf
   *          hadoop configuration object
   * @param fileName
   *          name of file to count
   * @return the number of lines in the file
   * @throws IOException
   */
  public static long getTotalNumberOfLines(Configuration conf, Path fileName) throws IOException {
    long nrLines = conf.getLong(NUMBER_OF_LINES_KEY, -1);
    if (nrLines != -1) {
      return nrLines;
    }
    
    try {
      FSDataInputStream in = fileName.getFileSystem(conf).open(fileName);
      LineReader lr = new LineReader(in, conf);
      Text text = new Text();
      nrLines = 0;
      while (lr.readLine(text) > 0) {
        nrLines++;
      }
      in.close();
      return nrLines;
    } catch (IOException e) {
      e.printStackTrace();
    }
    return 0;
  }
  
  /**
   * Creates a new filesplit object
   * 
   * @param fileName
   *          name of the file to which filesplit corresponds
   * @param begin
   *          begin of the split
   * @param length
   *          length of the split
   * @return file split object
   */
  protected static FileSplit createFileSplit(Path fileName, long begin, long length) {
    return (begin == 0) ? new FileSplit(fileName, begin, length - 1, new String[] {}) : new FileSplit(fileName,
        begin - 1, length, new String[] {});
  }
  
  /**
   * Get the number of splits
   * 
   * @param job
   *          the job
   * @return the number of splits to be created on this file
   */
  public static int getNumberOfSplits(JobContext job) {
    return job.getConfiguration().getInt(NUMBER_OF_CHUNKS, 1);
  }
}
