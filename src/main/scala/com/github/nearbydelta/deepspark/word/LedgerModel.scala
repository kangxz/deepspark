package com.github.nearbydelta.deepspark.word

import java.io._

import breeze.linalg.DenseVector
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import com.github.nearbydelta.deepspark.data._
import org.apache.log4j.Logger

import scala.collection.mutable
import scala.io.{Codec, Source}

/**
 * Class for word list, or ledger words.
 */
class LedgerWords extends Serializable with KryoSerializable {
  /** ID for pad */
  lazy final val padID = words.getOrElse(LedgerModel.PAD, -1)
  /** ID for Unknown */
  lazy final val unkID = words(LedgerModel.UnkAll)
  /** Mapping of words */
  val words = mutable.HashMap[String, Int]()

  /**
   * Get index of given word
   * @param str Word
   * @return Index
   */
  def indexOf(str: String): Int = {
    words.get(str) match {
      case Some(id) ⇒ id
      case None ⇒
        words.get(str.getShape) match {
          case Some(id) ⇒ id
          case None ⇒ unkID
        }
    }
  }

  /** Size of this layer */
  def size = words.size

  override def read(kryo: Kryo, input: Input): Unit = {
    words.clear()
    val size = input.readInt()
    (0 until size).foreach { _ ⇒
      val str = input.readString()
      val id = input.readInt()
      words += (str → id)
    }
  }

  // Please change word layer if change this.
  override def write(kryo: Kryo, output: Output): Unit = {
    output.writeInt(words.size)
    words.foreach {
      case (key, id) ⇒
        output.writeString(key)
        output.writeInt(id)
    }
  }
}

/**
 * Class for word vectors, or ledger.
 */
class LedgerModel extends Serializable with KryoSerializable {
  /** Dimension of vector */
  lazy final val dimension = vectors.head.size
  /** size of model */
  lazy final val size = map.size
  /** ID of Unknown */
  lazy final val unkID = map.unkID
  /** list of vector */
  val vectors = mutable.ArrayBuffer[DataVec]()
  /** map of words, ledger words. */
  var map = new LedgerWords()
  /** ID of pad. -1 if not using pad. */
  var padID = -1

  /**
   * Find vector of given string.
   * @param str word
   * @return Word vector
   */
  def apply(str: String): DataVec = {
    vectorAt(indexOf(str))
  }

  /**
   * Copy this ledger model
   * @return new ledger model.
   */
  def copy: LedgerModel = {
    val model = new LedgerModel().set(this.map, this.vectors)
    model
  }

  /**
   * Find index of given word
   * @param str word
   * @return index
   */
  def indexOf(str: String) = map.indexOf(str: String)

  /**
   * Save ledger model into text file
   * @param file Save path
   */
  def saveAsTextFile(file: String) =
    try {
      val bos = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)))
      map.words.foreach {
        case (word, id) ⇒
          val vec = vectors(id)
          bos.write(word + " " + vec.data.mkString(" ") + "\n")
      }
      bos.close()
    } catch {
      case _: Throwable ⇒
    }

  /**
   * Save ledger model into kryo file
   * @param file Save path
   */
  def saveTo(file: String) =
    try {
      val oos = new Output(new FileOutputStream(file))
      this.write(KryoWrap.get.kryo, oos)
      oos.close()
    } catch {
      case _: Throwable ⇒
    }

  /**
   * Set words and vectors
   * @param map word mapping, ledger words
   * @param vec sequence of vectors
   * @return self
   */
  def set(map: LedgerWords, vec: mutable.ArrayBuffer[DataVec]): this.type = {
    this.map = map
    padID = map.padID
    vectors ++= vec
    this
  }

  /**
   * Get vectors at index
   * @param at index
   * @return vector
   */
  def vectorAt(at: Int) = vectors(at)

  override def read(kryo: Kryo, input: Input): Unit = {
    map = kryo.readClassAndObject(input).asInstanceOf[LedgerWords]
    vectors.clear()
    val size = input.readInt()
    (0 until size).foreach { _ ⇒
      val vec = kryo.readClassAndObject(input).asInstanceOf[DataVec]
      vectors += vec
    }
    padID = input.readInt()
  }

  // Please change word layer if change this.
  override def write(kryo: Kryo, output: Output): Unit = {
    kryo.writeClassAndObject(output, map)
    output.writeInt(vectors.size)
    vectors.foreach {
      case vec ⇒
        kryo.writeClassAndObject(output, vec)
    }
    output.writeInt(padID)
  }
}

/**
 * Companion class of ledger model.
 */
object LedgerModel {
  /** String for pad */
  final val PAD: String = "#PAD_X"
  /** String for unknown */
  final val UnkAll = "#SHAPE_*"
  /** Logger */
  val logger = Logger.getLogger(this.getClass)

  /**
   * Read model from path. File can be white-space splited text list.
   * @param path Path of file
   * @return LedgerModel
   */
  def read(path: String): LedgerModel = read(new File(path))

  /**
   * Read model from path. File can be white-space splited text list.
   * @param file File
   * @return LedgerModel
   */
  def read(file: File): LedgerModel = {
    val path = new File(file.getPath + ".obj")
    if (path.exists) {
      val in = new Input(new FileInputStream(path))
      val model = new LedgerModel
      model.read(KryoWrap.get.kryo, in)
      in.close()

      logger info s"READ Embedding Vectors finished (Dimension ${model.dimension}, Size ${model.size})"
      model
    } else {
      val br = Source.fromFile(file)(Codec.UTF8).getLines()

      val wordmap = new LedgerWords()
      val vectors = mutable.ArrayBuffer[DataVec]()
      val unkCounts = mutable.HashMap[String, (Int, Int)]()
      val unkvecs = mutable.ArrayBuffer[DataVec]()
      var lineNo = 0

      while (br.hasNext) {
        if (lineNo % 10000 == 0)
          logger info f"READ GloVe file (${file.getName}) : $lineNo%9d"

        val line = br.next()
        val splits = line.trim().split("\\s+")
        val word = splits(0)
        val vector = DenseVector(splits.tail.map(_.toDouble))

        wordmap.words += word → lineNo
        vectors += vector
        lineNo += 1

        unkCounts.get(UnkAll) match {
          case Some((id, cnt)) ⇒
            unkvecs(id) += vector
            unkCounts(UnkAll) = (id, cnt + 1)
          case None ⇒
            unkCounts += UnkAll →(unkvecs.length, 1)
            unkvecs += vector.copy
        }

        val shape = word.getShape
        unkCounts.get(shape) match {
          case Some((id, cnt)) ⇒
            unkvecs(id) += vector
            unkCounts(shape) = (id, cnt + 1)
          case None ⇒
            unkCounts += shape →(unkvecs.length, 1)
            unkvecs += vector.copy
        }
      }

      val shapeThreshold = wordmap.words.size * 0.0001
      logger info s"Generate shapes for unknown vectors (${unkCounts.count(_._2._2 > shapeThreshold)} shapes)"

      unkCounts.par.map {
        case (shape, (id, count)) if count > shapeThreshold ⇒
          val matx = unkvecs(id)
          matx :/= count.toDouble
          wordmap.words += shape → vectors.length
          vectors += matx
        case _ ⇒
      }

      wordmap.words += PAD → lineNo
      vectors += vectors(wordmap.words(UnkAll)).copy

      val model = new LedgerModel().set(wordmap, vectors)
      logger info s"Start to save GloVe (Dimension ${model.dimension}, Size ${model.size})"
      model.saveTo(path.getPath)

      logger info s"READ Embedding Vectors finished (Dimension ${model.dimension}, Size ${model.size})"
      model
    }
  }
}