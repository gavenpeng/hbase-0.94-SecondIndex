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

package org.apache.hadoop.hbase.io;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.HFileArchiveUtil;

/**
 * HFileLink describes a link to an hfile.
 *
 * An hfile can be served from a region or from the hfile archive directory (/hbase/.archive)
 * HFileLink allows to access the referenced hfile regardless of the location where it is.
 *
 * <p>Searches for hfiles in the following order and locations:
 * <ul>
 *  <li>/hbase/table/region/cf/hfile</li>
 *  <li>/hbase/.archive/table/region/cf/hfile</li>
 * </ul>
 *
 * The link checks first in the original path if it is not present
 * it fallbacks to the archived path.
 */
@InterfaceAudience.Private
public class HFileLink extends FileLink {
  private static final Log LOG = LogFactory.getLog(HFileLink.class);

  /**
   * A non-capture group, for HFileLink, so that this can be embedded.
   * The HFileLink describe a link to an hfile in a different table/region
   * and the name is in the form: table=region-hfile.
   * <p>
   * Table name is ([a-zA-Z_0-9][a-zA-Z_0-9.-]*), so '=' is an invalid character for the table name.
   * Region name is ([a-f0-9]+), so '-' is an invalid character for the region name.
   * HFile is ([0-9a-f]+(?:_SeqId_[0-9]+_)?) covering the plain hfiles (uuid)
   * and the bulk loaded (_SeqId_[0-9]+_) hfiles.
   */
  public static final String LINK_NAME_REGEX =
    String.format("%s=%s-%s", HTableDescriptor.VALID_USER_TABLE_REGEX,
      HRegionInfo.ENCODED_REGION_NAME_REGEX, StoreFile.HFILE_NAME_REGEX);

  /** Define the HFile Link name parser in the form of: table=region-hfile */
  private static final Pattern LINK_NAME_PATTERN =
    Pattern.compile(String.format("^(%s)=(%s)-(%s)$", HTableDescriptor.VALID_USER_TABLE_REGEX,
      HRegionInfo.ENCODED_REGION_NAME_REGEX, StoreFile.HFILE_NAME_REGEX));

  /**
   * The link should be used for hfile and reference links
   * that can be found in /hbase/table/region/family/
   */
  private static final Pattern REF_OR_HFILE_LINK_PATTERN =
    Pattern.compile(String.format("^(%s)=(%s)-(.+)$", HTableDescriptor.VALID_USER_TABLE_REGEX,
      HRegionInfo.ENCODED_REGION_NAME_REGEX));

  private final Path archivePath;
  private final Path originPath;
  private final Path tempPath;

  /**
   * @param conf {@link Configuration} from which to extract specific archive locations
   * @param path The path of the HFile Link.
   * @throws IOException on unexpected error.
   */
  public HFileLink(Configuration conf, Path path) throws IOException {
    this(FSUtils.getRootDir(conf), HFileArchiveUtil.getArchivePath(conf), path);
  }

  /**
   * @param rootDir Path to the root directory where hbase files are stored
   * @param archiveDir Path to the hbase archive directory
   * @param path The path of the HFile Link.
   */
  public HFileLink(final Path rootDir, final Path archiveDir, final Path path) {
    Path hfilePath = getRelativeTablePath(path);
    this.tempPath = new Path(new Path(rootDir, HConstants.HBASE_TEMP_DIRECTORY), hfilePath);
    this.originPath = new Path(rootDir, hfilePath);
    this.archivePath = new Path(archiveDir, hfilePath);
    setLocations(originPath, tempPath, archivePath);
  }

  /**
   * @return the origin path of the hfile.
   */
  public Path getOriginPath() {
    return this.originPath;
  }

  /**
   * @return the path of the archived hfile.
   */
  public Path getArchivePath() {
    return this.archivePath;
  }

  /**
   * @param path Path to check.
   * @return True if the path is a HFileLink.
   */
  public static boolean isHFileLink(final Path path) {
    return isHFileLink(path.getName());
  }


  /**
   * @param fileName File name to check.
   * @return True if the path is a HFileLink.
   */
  public static boolean isHFileLink(String fileName) {
    Matcher m = LINK_NAME_PATTERN.matcher(fileName);
    if (!m.matches()) return false;

    return m.groupCount() > 2 && m.group(3) != null && m.group(2) != null && m.group(1) != null;
  }

  /**
   * Convert a HFileLink path to a table relative path.
   * e.g. the link: /hbase/test/0123/cf/testtb=4567-abcd
   *      becomes: /hbase/testtb/4567/cf/abcd
   *
   * @param path HFileLink path
   * @return Relative table path
   * @throws IOException on unexpected error.
   */
  private static Path getRelativeTablePath(final Path path) {
    // table=region-hfile
    Matcher m = REF_OR_HFILE_LINK_PATTERN.matcher(path.getName());
    if (!m.matches()) {
      throw new IllegalArgumentException(path.getName() + " is not a valid HFileLink name!");
    }

    // Convert the HFileLink name into a real table/region/cf/hfile path.
    String tableName = m.group(1);
    String regionName = m.group(2);
    String hfileName = m.group(3);
    String familyName = path.getParent().getName();
    return new Path(new Path(tableName, regionName), new Path(familyName, hfileName));
  }

  /**
   * Get the HFile name of the referenced link
   *
   * @param fileName HFileLink file name
   * @return the name of the referenced HFile
   */
  public static String getReferencedHFileName(final String fileName) {
    Matcher m = REF_OR_HFILE_LINK_PATTERN.matcher(fileName);
    if (!m.matches()) {
      throw new IllegalArgumentException(fileName + " is not a valid HFileLink name!");
    }
    return(m.group(3));
  }

  /**
   * Get the Region name of the referenced link
   *
   * @param fileName HFileLink file name
   * @return the name of the referenced Region
   */
  public static String getReferencedRegionName(final String fileName) {
    Matcher m = REF_OR_HFILE_LINK_PATTERN.matcher(fileName);
    if (!m.matches()) {
      throw new IllegalArgumentException(fileName + " is not a valid HFileLink name!");
    }
    return(m.group(2));
  }

  /**
   * Get the Table name of the referenced link
   *
   * @param fileName HFileLink file name
   * @return the name of the referenced Table
   */
  public static String getReferencedTableName(final String fileName) {
    Matcher m = REF_OR_HFILE_LINK_PATTERN.matcher(fileName);
    if (!m.matches()) {
      throw new IllegalArgumentException(fileName + " is not a valid HFileLink name!");
    }
    return(m.group(1));
  }

  /**
   * Create a new HFileLink name
   *
   * @param hfileRegionInfo - Linked HFile Region Info
   * @param hfileName - Linked HFile name
   * @return file name of the HFile Link
   */
  public static String createHFileLinkName(final HRegionInfo hfileRegionInfo,
      final String hfileName) {
    return createHFileLinkName(hfileRegionInfo.getTableNameAsString(),
                      hfileRegionInfo.getEncodedName(), hfileName);
  }

  /**
   * Create a new HFileLink name
   *
   * @param tableName - Linked HFile table name
   * @param regionName - Linked HFile region name
   * @param hfileName - Linked HFile name
   * @return file name of the HFile Link
   */
  public static String createHFileLinkName(final String tableName,
      final String regionName, final String hfileName) {
    return String.format("%s=%s-%s", tableName, regionName, hfileName);
  }

  /**
   * Create a new HFileLink
   *
   * <p>It also adds a back-reference to the hfile back-reference directory
   * to simplify the reference-count and the cleaning process.
   *
   * @param conf {@link Configuration} to read for the archive directory name
   * @param fs {@link FileSystem} on which to write the HFileLink
   * @param dstFamilyPath - Destination path (table/region/cf/)
   * @param hfileRegionInfo - Linked HFile Region Info
   * @param hfileName - Linked HFile name
   * @return true if the file is created, otherwise the file exists.
   * @throws IOException on file or parent directory creation failure
   */
  public static boolean create(final Configuration conf, final FileSystem fs,
      final Path dstFamilyPath, final HRegionInfo hfileRegionInfo,
      final String hfileName) throws IOException {
    String linkedTable = hfileRegionInfo.getTableNameAsString();
    String linkedRegion = hfileRegionInfo.getEncodedName();
    return create(conf, fs, dstFamilyPath, linkedTable, linkedRegion, hfileName);
  }

  /**
   * Create a new HFileLink
   *
   * <p>It also adds a back-reference to the hfile back-reference directory
   * to simplify the reference-count and the cleaning process.
   *
   * @param conf {@link Configuration} to read for the archive directory name
   * @param fs {@link FileSystem} on which to write the HFileLink
   * @param dstFamilyPath - Destination path (table/region/cf/)
   * @param linkedTable - Linked Table Name
   * @param linkedRegion - Linked Region Name
   * @param hfileName - Linked HFile name
   * @return true if the file is created, otherwise the file exists.
   * @throws IOException on file or parent directory creation failure
   */
  public static boolean create(final Configuration conf, final FileSystem fs,
      final Path dstFamilyPath, final String linkedTable, final String linkedRegion,
      final String hfileName) throws IOException {
    String familyName = dstFamilyPath.getName();
    String regionName = dstFamilyPath.getParent().getName();
    String tableName = dstFamilyPath.getParent().getParent().getName();

    String name = createHFileLinkName(linkedTable, linkedRegion, hfileName);
    String refName = createBackReferenceName(tableName, regionName);

    // Make sure the destination directory exists
    fs.mkdirs(dstFamilyPath);

    // Make sure the FileLink reference directory exists
    Path archiveStoreDir = HFileArchiveUtil.getStoreArchivePath(conf,
          linkedTable, linkedRegion, familyName);
    Path backRefssDir = getBackReferencesDir(archiveStoreDir, hfileName);
    fs.mkdirs(backRefssDir);

    // Create the reference for the link
    Path backRefPath = new Path(backRefssDir, refName);
    fs.createNewFile(backRefPath);
    try {
      // Create the link
      return fs.createNewFile(new Path(dstFamilyPath, name));
    } catch (IOException e) {
      LOG.error("couldn't create the link=" + name + " for " + dstFamilyPath, e);
      // Revert the reference if the link creation failed
      fs.delete(backRefPath, false);
      throw e;
    }
  }

  /**
   * Create a new HFileLink starting from a hfileLink name
   *
   * <p>It also adds a back-reference to the hfile back-reference directory
   * to simplify the reference-count and the cleaning process.
   *
   * @param conf {@link Configuration} to read for the archive directory name
   * @param fs {@link FileSystem} on which to write the HFileLink
   * @param dstFamilyPath - Destination path (table/region/cf/)
   * @param hfileLinkName - HFileLink name (it contains hfile-region-table)
   * @return true if the file is created, otherwise the file exists.
   * @throws IOException on file or parent directory creation failure
   */
  public static boolean createFromHFileLink(final Configuration conf, final FileSystem fs,
      final Path dstFamilyPath, final String hfileLinkName) throws IOException {
    Matcher m = LINK_NAME_PATTERN.matcher(hfileLinkName);
    if (!m.matches()) {
      throw new IllegalArgumentException(hfileLinkName + " is not a valid HFileLink name!");
    }
    return create(conf, fs, dstFamilyPath, m.group(1), m.group(2), m.group(3));
  }

  /**
   * Create the back reference name
   */
  private static String createBackReferenceName(final String tableName, final String regionName) {
    return regionName + "." + tableName;
  }

  /**
   * Get the full path of the HFile referenced by the back reference
   *
   * @param rootDir root hbase directory
   * @param linkRefPath Link Back Reference path
   * @return full path of the referenced hfile
   * @throws IOException on unexpected error.
   */
  public static Path getHFileFromBackReference(final Path rootDir, final Path linkRefPath) {
    int separatorIndex = linkRefPath.getName().indexOf('.');
    String linkRegionName = linkRefPath.getName().substring(0, separatorIndex);
    String linkTableName = linkRefPath.getName().substring(separatorIndex + 1);
    String hfileName = getBackReferenceFileName(linkRefPath.getParent());
    Path familyPath = linkRefPath.getParent().getParent();
    Path regionPath = familyPath.getParent();
    Path tablePath = regionPath.getParent();

    String linkName = createHFileLinkName(tablePath.getName(), regionPath.getName(), hfileName);
    Path linkTableDir = FSUtils.getTablePath(rootDir, linkTableName);
    Path regionDir = HRegion.getRegionDir(linkTableDir, linkRegionName);
    return new Path(new Path(regionDir, familyPath.getName()), linkName);
  }

  /**
   * Get the full path of the HFile referenced by the back reference
   *
   * @param conf {@link Configuration} to read for the archive directory name
   * @param linkRefPath Link Back Reference path
   * @return full path of the referenced hfile
   * @throws IOException on unexpected error.
   */
  public static Path getHFileFromBackReference(final Configuration conf, final Path linkRefPath)
      throws IOException {
    return getHFileFromBackReference(FSUtils.getRootDir(conf), linkRefPath);
  }
}
