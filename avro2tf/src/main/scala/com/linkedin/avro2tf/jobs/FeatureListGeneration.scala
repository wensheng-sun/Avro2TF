package com.linkedin.avro2tf.jobs

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable

import com.linkedin.avro2tf.helpers.TensorizeInConfigHelper
import com.linkedin.avro2tf.parsers.TensorizeInParams
import com.linkedin.avro2tf.utils.CommonUtils
import com.linkedin.avro2tf.utils.Constants._

import org.apache.hadoop.fs.{FileSystem, FileUtil, Path}
import org.apache.spark.sql.functions.{col, concat_ws}
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.{DataFrame, Row}

/**
 * The Feature List Generation job generates feature list that will be later used in training with tensors.
 *
 */
class FeatureListGeneration {

  /**
   * The main function to perform Feature List Generation job
   *
   * @param dataFrame Input data Spark DataFrame
   * @param params TensorizeIn parameters specified by user
   */
  def run(dataFrame: DataFrame, params: TensorizeInParams): Unit = {

    val fileSystem = FileSystem.get(dataFrame.sparkSession.sparkContext.hadoopConfiguration)

    val featureListPath = new Path(params.workingDir.featureListPath)
    // Make sure we have an empty feature list dir before generating new ones
    fileSystem.delete(featureListPath, ENABLE_RECURSIVE)
    fileSystem.mkdirs(featureListPath)

    // Only collect those without external feature list and hash information specified in TensorizeIn configuration
    val colsToCollectFeatureList = TensorizeInConfigHelper.concatFeaturesAndLabels(params)
      .map(featureOrLabel => featureOrLabel.outputTensorInfo.name) diff
      (processExternalFeatureList(params, fileSystem) ++ TensorizeInConfigHelper.getColsWithHashInfo(params))

    // Make sure tensors with feature list sharing settings all exist in colsToCollectFeatureList
    params.tensorsSharingFeatureLists match {
      case Some(tensorsGroups) => {
        val tensorsInGroups = tensorsGroups.flatten
        if (!tensorsInGroups.forall(tensor => colsToCollectFeatureList.contains(tensor))) {
          throw new IllegalArgumentException(
            s"Settings in --tensors-sharing-feature-lists conflict with other " +
              s"settings. Some tensors in --tensors-sharing-feature-lists are not part of those that the job is " +
              s"collecting feature list for. Most likely, they have external feature list or has hashing setting." +
              s"Tensors in --tensors-sharing-feature-lists: $tensorsGroups. Tensors the job collect feature list for: " +
              s"$colsToCollectFeatureList.")
        }
      }
      case None => ()
    }

    val ntvTensors = collectAndSaveFeatureList(dataFrame, params, fileSystem, colsToCollectFeatureList)
    writeFeatureList(params, fileSystem, ntvTensors)

    fileSystem.close()
  }

  /**
   * Process external feature lists by copying to working directory and collecting their column names
   *
   * @param params TensorizeIn parameters specified by user
   * @param fileSystem A file system
   * @return A sequence of column names
   */
  private def processExternalFeatureList(params: TensorizeInParams, fileSystem: FileSystem): Seq[String] = {

    if (!params.externalFeaturesListPath.isEmpty) {
      val colsWithExternalFeatureList = new mutable.HashSet[String]
      val colsWithHashInfo = TensorizeInConfigHelper.getColsWithHashInfo(params)

      // Get list statuses and block locations of the external feature list files from the given path
      val externalFeatureListFiles = fileSystem.listFiles(new Path(params.externalFeaturesListPath), ENABLE_RECURSIVE)
      val destinationPath = params.workingDir.featureListPath

      while (externalFeatureListFiles.hasNext) {
        // Get the source path of external feature list file
        val sourcePath = externalFeatureListFiles.next().getPath
        // Get the column name of external feature list
        // (Note: User is required to use the corresponding column name as their external feature list file name)
        val columnName = sourcePath.getName

        // In case user does not specify a right external feature list which they want to use
        if (!TensorizeInConfigHelper.concatFeaturesAndLabels(params)
          .map(featureOrLabel => featureOrLabel.outputTensorInfo.name).contains(columnName)) {
          throw new IllegalArgumentException(s"External feature list $columnName does not exist in user specified TensorizeIn output tensor names.")
        }

        // Exclude external feature list of columns with hash information
        if (!colsWithHashInfo.contains(columnName)) {
          colsWithExternalFeatureList.add(columnName)
          // Move external feature list path to destination path with its column name as file name
          FileUtil.copy(
            fileSystem, sourcePath, fileSystem, new Path(s"$destinationPath/$columnName"),
            DISABLE_DELETE_SOURCE, ENABLE_HDFS_OVERWRITE, fileSystem.getConf)
        }
      }

      colsWithExternalFeatureList.toSeq
    } else {
      Seq.empty
    }
  }

  /**
   * Collect and save feature list
   *
   * @param dataFrame Input data Spark DataFrame
   * @param params TensorizeIn parameters specified by user
   * @param fileSystem A file system
   * @param colsToCollectFeatureList A sequence of columns to collect feature lists
   */
  private def collectAndSaveFeatureList(
    dataFrame: DataFrame,
    params: TensorizeInParams,
    fileSystem: FileSystem,
    colsToCollectFeatureList: Seq[String]): mutable.HashSet[String] = {

    import dataFrame.sparkSession.implicits._
    val dataFrameSchema = dataFrame.schema
    val tmpFeatureListPath = s"${params.workingDir.rootPath}/$TMP_FEATURE_LIST"
    fileSystem.delete(new Path(tmpFeatureListPath), ENABLE_RECURSIVE)
    val outputTensorDataTypes = TensorizeInConfigHelper.getOutputTensorDataTypes(params)

    // collect NTV tensors
    val ntvTensors = new mutable.HashSet[String]()
    colsToCollectFeatureList.foreach {
      colName => {
        if (CommonUtils.isArrayOfNTV(dataFrameSchema(colName).dataType)) {
          ntvTensors += colName
        }
      }
    }
    dataFrame.flatMap {
      row => {
        colsToCollectFeatureList.flatMap {
          colName => {
            if (CommonUtils.isArrayOfNTV(dataFrameSchema(colName).dataType)) {
              val ntvs = row.getAs[Seq[Row]](colName)
              if (ntvs != null) {
                ntvs.map(
                  ntv => TensorizeIn
                    .FeatureListEntry(colName, s"${ntv.getAs[String](NTV_NAME)},${ntv.getAs[String](NTV_TERM)}"))
              } else {
                Seq.empty
              }
            } else if (CommonUtils.isArrayOfString(dataFrameSchema(colName).dataType) &&
              CommonUtils.isIntegerTensor(outputTensorDataTypes(colName))) {
              val columnNames = row.getAs[Seq[String]](colName)

              if (columnNames != null) {
                columnNames.map(string => TensorizeIn.FeatureListEntry(colName, string))
              } else {
                Seq.empty
              }
            } else if (dataFrameSchema(colName).dataType.isInstanceOf[StringType] &&
              CommonUtils.isIntegerTensor(outputTensorDataTypes(colName))) {
              val columnName = row.getAs[String](colName)

              if (columnName != null) {
                Seq(TensorizeIn.FeatureListEntry(colName, columnName))
              } else {
                Seq.empty
              }
            }
            else {
              Seq.empty
            }
          }
        }
      }
    }
      .groupBy(COLUMN_NAME, FEATURE_ENTRY)
      .count()
      .select(
        col(COLUMN_NAME),
        concat_ws(SEPARATOR_FEATURE_COUNT, col(FEATURE_ENTRY), col(COUNT)).name(FEATURE_ENTRY))
      .write
      .partitionBy(COLUMN_NAME)
      .text(tmpFeatureListPath)
    ntvTensors
  }

  /**
   * Write feature list as text file to HDFS
   *
   * @param params TensorizeIn parameters specified by user
   * @param fileSystem A file system
   */
  private def writeFeatureList(
    params: TensorizeInParams,
    fileSystem: FileSystem,
    ntvTensors: mutable.HashSet[String]): Unit = {

    /**
     * Write feature list to disk for a list of (feature entry, count) pairs
     *
     * @param p Path to file to create and write
     * @param featureEntriesWCount a list of (feature entry, count) pairs
     */
    def writeFeatureEntriesWCountToDisk(
      p: Path,
      featureEntriesWCount: Seq[(String, Int)],
      prefix: Option[String]): Unit = {

      val outputStream = fileSystem.create(p)
      val writer = new OutputStreamWriter(outputStream, UTF_8.name())
      prefix match {
        case Some(prefixString)
        => {
          featureEntriesWCount.foreach {
            case (featureEntry, _) => writer.write(s"$prefixString,$featureEntry\n")
          }
        }
        case None => {
          featureEntriesWCount.foreach {
            case (featureEntry, _) => writer.write(s"$featureEntry\n")
          }
        }
      }
      writer.close()
    }

    // first get a set of tensor names for which temporary feature list files have been collected
    val allColsToWriteFeatureLists = new mutable.HashSet[String]()
    val tmpFeatureListDir = s"${params.workingDir.rootPath}/$TMP_FEATURE_LIST"
    allColsToWriteFeatureLists ++= fileSystem.listStatus(new Path(tmpFeatureListDir)).filter(_.isDirectory())
      .filter(_.getPath.getName.startsWith(s"$COLUMN_NAME=")).map(_.getPath.getName.split(s"$COLUMN_NAME=").last)
    val tensorGroups = new mutable.ArrayBuffer[Array[String]]
    params.tensorsSharingFeatureLists match {
      case Some(tensorSharingGroups) => {
        tensorGroups ++= tensorSharingGroups
        allColsToWriteFeatureLists --= tensorSharingGroups.flatten.toSet
      }
      case None => ()
    }
    if (allColsToWriteFeatureLists.nonEmpty) {
      tensorGroups ++= allColsToWriteFeatureLists.map(tensor => Array(tensor))
    }
    // merge and write feature lists for output tensors with shared feature list setting
    tensorGroups.foreach( // each element is an array containing the output tensor names sharing one feature list
      tensors => {
        // for the current group of tensors sharing one feature list, accumulate feature entry count in a hashmap
        val featureEntriesWCount = new mutable.HashMap[String, Int]().withDefaultValue(0)
        // get a list of tensors where a prefix needs to be removed when accumulating feature entry count and added back
        // when writing the feature entries out. These are ntv tensors with feature sharing setting
        val tensorsWithPrefix = new mutable.HashMap[String, String]()
        tensors.foreach( // go over the temporary feature list files for each output tensor
          tensor => {
            // determine whether the tensor needs a special prefix treatment
            val needProcessPrefix: Boolean = tensors.size > 1 && ntvTensors.contains(tensor)
            val prefix = new mutable.HashSet[String]() // to make sure there is only one prefix per tensor
            val featureListDirForCurrentTensor = new Path(s"$tmpFeatureListDir/$COLUMN_NAME=$tensor")
            val filesIterator = fileSystem.listFiles(featureListDirForCurrentTensor, ENABLE_RECURSIVE)
            while (filesIterator.hasNext) {
              val tmpFeatureListPath = filesIterator.next().getPath
              val fileInputStream = fileSystem.open(tmpFeatureListPath)
              scala.io.Source.fromInputStream(fileInputStream, UTF_8.name()).getLines()
                .foreach(
                  line => {
                    // the format of each line is feature_entry,count
                    val featureEntry = if (needProcessPrefix) {
                      val prefixCurrentLine = line.split(SPLIT_REGEX).head.split(',').head
                      if (prefix.isEmpty) {
                        prefix += prefixCurrentLine
                        tensorsWithPrefix += tensor -> prefixCurrentLine
                      }
                      else {
                        if (!prefix.contains(prefixCurrentLine)) {
                          throw new IllegalArgumentException(
                            s"Output tensors of NTV type with feature sharing settings can only have 1 value for " +
                              s"'name' across the data set. Detected ${prefix} and ${prefixCurrentLine} for ${tensor}."
                          )
                        }
                      }
                      line.split(SPLIT_REGEX).head.split(',').last
                    }
                    else {
                      line.split(SPLIT_REGEX).head
                    }
                    val count = line.split(SEPARATOR_FEATURE_COUNT).last.toInt
                    featureEntriesWCount(featureEntry) += count
                  })
              fileInputStream.close()
            }
          }
        )
        // sort the feature list by count and then by feature entry (alphabetically)
        val featureList = featureEntriesWCount.toSeq.sortBy { case (k, v) => (-v, k) }
        // write out feature list file for each output tensor in the current group
        tensors.foreach(
          tensor => {
            val outputPath = new Path(s"${params.workingDir.featureListPath}/$tensor")
            val prefix: Option[String] = if (tensorsWithPrefix.contains(tensor)) {
              Some(tensorsWithPrefix(tensor))
            }
            else {
              None
            }
            writeFeatureEntriesWCountToDisk(outputPath, featureList, prefix)
          }
        )
      }
    )
  }
}