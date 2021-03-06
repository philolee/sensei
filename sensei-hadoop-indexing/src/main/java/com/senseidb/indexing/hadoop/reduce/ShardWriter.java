/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.senseidb.indexing.hadoop.reduce;

import java.io.File;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Trash;
import org.apache.log4j.Logger;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.KeepOnlyLastCommitDeletionPolicy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

import com.senseidb.indexing.hadoop.keyvalueformat.IntermediateForm;
import com.senseidb.indexing.hadoop.keyvalueformat.Shard;
import com.senseidb.indexing.hadoop.util.SenseiJobConfig;

/**
 * The initial version of an index is stored in the perm dir. Index files
 * created by newer versions are written to a temp dir on the local FS. After
 * successfully creating the new version in the temp dir, the shard writer
 * moves the new files to the perm dir and deletes the temp dir in close().
 */
public class ShardWriter {
  private static Logger logger = Logger.getLogger(ShardWriter.class);

  private final FileSystem fs;
  private final FileSystem localFs;
  private final Path perm;
  private final Path temp;
  // private final Directory dir;
  private final IndexWriter writer;
  private int maxNumSegments;
  private long numForms = 0;
  private final Configuration iconf;

  /**
   * Constructor
   * @param fs
   * @param shard
   * @param tempDir
   * @param iconf
   * @throws IOException
   */
  public ShardWriter(FileSystem fs, Shard shard, String tempDir, Configuration iconf)
      throws IOException {
    logger.info("Construct a shard writer");

    this.iconf = iconf;
    this.fs = fs;
    localFs = FileSystem.getLocal(iconf);
    perm = new Path(shard.getDirectory());
    temp = new Path(tempDir);

    long initGeneration = shard.getGeneration();

    if (localFs.exists(temp)) {
      File tempFile = new File(temp.getName());
      if (tempFile.exists()) SenseiReducer.deleteDir(tempFile);
    }

    if (!fs.exists(perm)) {
      assert (initGeneration < 0);
      fs.mkdirs(perm);
    } else {
      moveToTrash(iconf, perm);
      fs.mkdirs(perm);
    }

    IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_43, null);
    config.setIndexDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy());
    writer = new IndexWriter(FSDirectory.open(new File(tempDir)), config);
    setParameters(iconf);
  }

  /**
   * Process an intermediate form by carrying out, on the Lucene instance of
   * the shard, the deletes and the inserts (a ram index) in the form.
   * @param form  the intermediate form containing deletes and a ram index
   * @throws IOException
   */
  public void process(IntermediateForm form) throws IOException {
    writer.addIndexes(new Directory[] { form.getDirectory() });
    numForms++;
  }

  /**
   * Close the shard writer. Optimize the Lucene instance of the shard before
   * closing if necessary, and copy the files created in the temp directory
   * to the permanent directory after closing.
   * @throws IOException
   */
  public void close() throws IOException {
    logger.info("Closing the shard writer, processed " + numForms + " forms");
    try {
      try {
        if (maxNumSegments > 0) {
          writer.forceMerge(maxNumSegments);
          logger.info("Optimized the shard into at most " + maxNumSegments + " segments");
        }
      } finally {
        writer.close();
        logger.info("Closed Lucene index writer");
      }

      moveFromTempToPerm();
      logger.info("Moved new index files to " + perm);

    } finally {
      // dir.close();
      logger.info("Closed the shard writer");
    }
  }

  /*
   * (non-Javadoc)
   * @see java.lang.Object#toString()
   */
  @Override
  public String toString() {
    return this.getClass().getName() + "@" + perm + "&" + temp;
  }

  private void setParameters(Configuration conf) {
    maxNumSegments = conf.getInt(SenseiJobConfig.MAX_NUM_SEGMENTS, -1);
    logger.info(SenseiJobConfig.MAX_NUM_SEGMENTS + " = " + maxNumSegments);
  }

  private void moveFromTempToPerm() throws IOException {

    FileStatus[] fileStatus = localFs.listStatus(temp);

    // move the files created in temp dir except segments_N and segments.gen
    for (int i = 0; i < fileStatus.length; i++) {
      Path path = fileStatus[i].getPath();
      String name = path.getName();

      try {
        if (!fs.exists(new Path(perm, name))) {
          fs.copyFromLocalFile(path, new Path(perm, name));
        } else {
          moveToTrash(iconf, perm);
          fs.copyFromLocalFile(path, new Path(perm, name));
        }
      } catch (Exception e) {
        ;
      }

    }

  }

  public void optimize() {
    try {
      writer.forceMerge(1);
    } catch (CorruptIndexException e) {
      logger.error("Corrupt Index error. ", e);
    } catch (IOException e) {
      logger.error("IOException during index optimization. ", e);
    }
  }

  public static void moveToTrash(Configuration conf, Path path) throws IOException {
    Trash t = new Trash(conf);
    boolean isMoved = t.moveToTrash(path);
    t.expunge();
    if (!isMoved) {
      logger.error("Trash is not enabled or file is already in the trash.");
    }
  }
}
