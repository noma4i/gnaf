package au.csiro.data61.gnaf.test

import java.util.concurrent.{ ArrayBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit }

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import ExecutionContext.Implicits.global

import scala.language.implicitConversions
import scala.math.BigDecimal
import scala.util.{ Failure, Success }

import com.typesafe.config.{ ConfigFactory, ConfigValueFactory }

import au.csiro.data61.gnaf.db.GnafTables
import au.csiro.data61.gnaf.util.Util
import resource.managed
import slick.collection.heterogeneous._
import slick.collection.heterogeneous.syntax._
import scala.util.Random

import spray.json.{ pimpAny, DefaultJsonProtocol }

// Organize Imports deletes this, so make it easy to restore ...
// import slick.collection.heterogeneous.syntax.::

object Main {
  val log = Util.getLogger(getClass)  
  val config = ConfigFactory.load
  
  object MyGnafTables extends {
    val profile = Util.getObject[slick.driver.JdbcProfile](config.getString("gnafDb.slickDriver")) // e.g. slick.driver.{H2Driver,PostgresDriver}
  } with GnafTables
  import MyGnafTables._
  import MyGnafTables.profile.api._

  /** result of command line option processing */
  case class CliOption(
      dburl: String,
      sampleSize: Int,
      noFlatType: Boolean = false,
      numberAdornments: Boolean = false, // with number prefix, suffix or range
      flat: Boolean = false, // with flat number
      level: Boolean = false, // with level number
      streetAlias: Boolean = false,
      localityAlias: Boolean = false
      )
  val defaultCliOption = CliOption(config.getString("gnafDb.url"), config.getInt("gnafTest.sampleSize"))

  /**
   * Generate addresses for testing.
   */
  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[CliOption]("gnaf-indexer") {
      head("gnaf-test", "0.x")
      note("Generate addresses for testing")
      opt[String]('d', "dburl") action { (x, c) =>
        c.copy(dburl = x)
      } text (s"database URL, default ${defaultCliOption.dburl}")
     opt[Int]('s', "sampleSize") action { (x, c) =>
        c.copy(sampleSize = x)
      } text (s"test sample size, default ${defaultCliOption.sampleSize}")
     opt[Unit]('t', "noFlatType") action { (_, c) =>
        c.copy(noFlatType = true)
      } text (s"addresses with a flat and without a level have the flat type ommitted, default ${defaultCliOption.noFlatType}")
     opt[Unit]('n', "numberAdornments") action { (_, c) =>
        c.copy(numberAdornments = true)
      } text (s"addresses with number prefix, suffix or range, default ${defaultCliOption.numberAdornments}")
     opt[Unit]('f', "flat") action { (_, c) =>
        c.copy(flat = true)
      } text (s"addresses with flat number, default ${defaultCliOption.flat}")
     opt[Unit]('l', "level") action { (_, c) =>
        c.copy(level = true)
      } text (s"addresses with level number, default ${defaultCliOption.level}")
     opt[Unit]('x', "streetAlias") action { (_, c) =>
        c.copy(streetAlias = true)
      } text (s"addresses with street alias, default ${defaultCliOption.streetAlias}")
     opt[Unit]('y', "localityAlias") action { (_, c) =>
        c.copy(localityAlias = true)
      } text (s"addresses with locality alias, default ${defaultCliOption.localityAlias}")
      help("help") text ("prints this usage text")
    }
    parser.parse(args, defaultCliOption) foreach run
    log.info("done")
  }

  def run(c: CliOption) = {
    val conf = config.withValue("gnafDb.url", ConfigValueFactory.fromAnyRef(c.dburl)) // CliOption.dburl overrides gnafDb.url
    for (db <- managed(Database.forConfig("gnafDb", conf))) {
      doAll(c)(db)
    }
  }
  
  val qLocalitiesWithoutAliases = {
    val q = for {
      // unfortunately checking that a locality has adresses makes it really slow
//      ((l, la), ad) <- Locality joinLeft 
//      LocalityAlias on (_.localityPid === _.localityPid) joinLeft
//      AddressDetail on (_._1.localityPid === _.localityPid)
//      if la.isEmpty && ad.nonEmpty
      (l, la) <- Locality joinLeft 
      LocalityAlias on (_.localityPid === _.localityPid)
      if l.localityClassCode === 'G' && la.isEmpty
    } yield l
    Compiled(q)
  }
    
  val qLocalitiesWithAliases = {
    val q = for {
      // unfortunately checking that a locality has adresses makes it really slow
//      ((l, la), ad) <- Locality join 
//      LocalityAlias on (_.localityPid === _.localityPid) joinLeft
//      AddressDetail on (_._1.localityPid === _.localityPid)
//      if ad.nonEmpty
      (l, la) <- Locality join 
      LocalityAlias on (_.localityPid === _.localityPid)
      if l.localityClassCode === 'G'
    } yield (l, la)
    Compiled(q)
  }
  
  /** make the return type common */
  def localities(localityAlias: Boolean)(implicit db: Database): Future[Seq[(LocalityRow, Seq[LocalityAliasRow])]] = {
    if (localityAlias) db.run(qLocalitiesWithAliases.result).map(_.groupBy(_._1.localityPid).toSeq.map { case (_, s) => s.head._1 -> s.map(_._2) })
    else db.run(qLocalitiesWithoutAliases.result).map(_.map(l => (l, Seq.empty)))
  }
    
  /**
   *  See if slick criteria could collapse next 4 methods into 1 - they differ only in the if/where clause.
   *  http://slick.lightbend.com/doc/3.1.1/queries.html#sorting-and-filtering:
   *  
          // building criteria using a "dynamic filter" e.g. from a webform.
          val criteriaColombian = Option("Colombian")
          val criteriaEspresso = Option("Espresso")
          val criteriaRoast:Option[String] = None
          
          val q4 = coffees.filter { coffee =>
            List(
                criteriaColombian.map(coffee.name === _),
                criteriaEspresso.map(coffee.name === _),
                criteriaRoast.map(coffee.name === _) // not a condition as `criteriaRoast` evaluates to `None`
            ).collect({case Some(criteria)  => criteria}).reduceLeftOption(_ || _).getOrElse(true: Rep[Boolean])
          }
          
          // how does this work with joins?
          .filter { ((ad, sl), sla) =>
             List(
               cAdName.map(ad.name === _),
               cSlName.map(sl.name === _)
             ).collect({case Some(criteria)  => criteria}).reduceLeftOption(_ || _).getOrElse(true: Rep[Boolean])
             
          // how does this extend to a && (b || c)? I guess like this:
          .filter { ((ad, sl), sla) =>
             val orClause = List(
               cAdName.map(ad.name === _),
               cSlName.map(sl.name === _)
             ).collect({case Some(criteria)  => criteria}).reduceLeftOption(_ || _)
             List(
               cAdName.map(ad.name === _),
               orClause
             ).collect({case Some(criteria)  => criteria}).reduceLeftOption(_ && _).getOrElse(true: Rep[Boolean])
          
          // getting messy, not an improvement over the duplication below.
  */
  
  def qAddressDetailBase(localityPid: Rep[String]) = for {
    ((((ad, as), l), sl), sla) <- AddressDetail join
      AddressSite on (_.addressSitePid === _.addressSitePid) join
      Locality on (_._1.localityPid === _.localityPid) join
      StreetLocality on (_._1._1.streetLocalityPid === _.streetLocalityPid) joinLeft
      StreetLocalityAlias on (_._1._1._1.streetLocalityPid === _.streetLocalityPid)
      if ad.localityPid === localityPid && ad.confidence > -1
  } yield (ad, as, sl, sla)

  /** addresses without: number prefix, suffix or range; flat; level; or street alias */
  val qAddressDetailWithoutNumberAdornments = {
    def q(localityPid: Rep[String]) = qAddressDetailBase(localityPid).filter { case (ad, as, sl, sla) =>
      ad.numberFirstPrefix.isEmpty && ad.numberFirstSuffix.isEmpty && ad.numberLast.isEmpty && ad.flatNumber.isEmpty && ad.levelNumber.isEmpty && sla.isEmpty
    }
    Compiled(q _)
  }

  val qAddressDetailWithNumberAdornments = {
    def q(localityPid: Rep[String]) = qAddressDetailBase(localityPid).filter { case (ad, _, _, _) =>
      ad.numberFirstPrefix.nonEmpty || ad.numberFirstSuffix.nonEmpty || ad.numberLast.nonEmpty
    }
    Compiled(q _)
  }
  
  val qAddressDetailWithFlat = {
    def q(localityPid: Rep[String]) = qAddressDetailBase(localityPid).filter { case (ad, _, _, _) =>
      ad.flatNumber.nonEmpty
    }
    Compiled(q _)
  }
  
  val qAddressDetailWithLevel = {
    def q(localityPid: Rep[String]) = qAddressDetailBase(localityPid).filter { case (ad, _, _, _) =>
      ad.levelNumber.nonEmpty
    }
    Compiled(q _)
  }
  
  val qAddressDetailWithStreetAlias = {
    def q(localityPid: Rep[String]) = qAddressDetailBase(localityPid).filter { case (_, _, _, sla) =>
      sla.nonEmpty
    }
    Compiled(q _)
  }
  
  def addressDetail(c: CliOption, localityPid: String)(implicit db: Database)
  : Future[Seq[(AddressDetailRow, AddressSiteRow, StreetLocalityRow, Seq[StreetLocalityAliasRow])]]
  = {
    val q = if (c.numberAdornments) qAddressDetailWithNumberAdornments(localityPid)
      else if (c.flat) qAddressDetailWithFlat(localityPid)
      else if (c.level) qAddressDetailWithLevel(localityPid)
      else if (c.streetAlias) qAddressDetailWithStreetAlias(localityPid)
      else qAddressDetailWithoutNumberAdornments(localityPid)
    db.run(q.result).map(
      _.groupBy(_._1.head) // addressDetailPid
      .toSeq.map { case (_, seq) =>
        val (ad, as, sl, _) = seq.head
        (ad, as, sl, seq.flatMap(_._4))
      }
    )
  }

  type FutStrMap = Future[Map[String, String]]
  trait Lookups {
    // These code -> name mappings are all small enough to keep in memory
    val stateMap: FutStrMap
    val flatTypeMap: FutStrMap
    val levelTypeMap: FutStrMap
    // val streetTypeMap: FutStrMap // code is actually the full street type, name is the abbreviation
    val streetSuffixMap: FutStrMap
  }
  
  case class Addr(query: String, queryPostcodeBeforeState: String, queryTypo: String, addressDetailPid: String, address: String)

  object JsonProtocol extends DefaultJsonProtocol {
    implicit val addrFormat = jsonFormat5(Addr)
  }
  import JsonProtocol._

  def doAll(c: CliOption)(implicit db: Database): Unit = {
    log.debug(s"config = $c")
    
    implicit val lookups = new Lookups {
      val stateMap = db.run((for (s <- State) yield s.statePid -> s.stateAbbreviation).result).map(_.toMap)
      val flatTypeMap = db.run((for (f <- FlatTypeAut) yield f.code -> f.name).result).map(_.toMap)
      val levelTypeMap = db.run((for (f <- LevelTypeAut) yield f.code -> f.name).result).map(_.toMap)
      // val streetTypeMap = db.run((for (s <- StreetTypeAut) yield s.code -> s.name).result).map(_.toMap)
      val streetSuffixMap = db.run((for (s <- StreetSuffixAut) yield s.code -> s.name).result).map(_.toMap)
    }
    
    val fAddresses = localities(c.localityAlias).flatMap { seqLoc =>
      val addrPerLoc = c.sampleSize / seqLoc.size
      val remainder = c.sampleSize - addrPerLoc * seqLoc.size
      def numAddr(i: Int) = {
        val n = if (i < remainder) addrPerLoc + 1 else addrPerLoc
        log.debug(s"numAddr($i) = $n")
        n
      }
      val seqFut = Random.shuffle(seqLoc).take(c.sampleSize).zipWithIndex.map { case ((l, a), i) => toAddresses(c, l, a, numAddr(i)) }
      Future.fold[Seq[Addr], Seq[Addr]](seqFut)(Seq.empty)(_ ++ _)
    }
    val addresses = Await.result(fAddresses, 15.minute)
    println(addresses.toJson.compactPrint)
  }
      
  def getRandomElement[T](s: Seq[T]): T = s(Random.nextInt(s.size))
  
  def join(s: Seq[Option[String]], delim: String): Option[String] = {
    val r = s.flatten.mkString(delim)
    if (r.nonEmpty) Some(r) else None
  }
  
  def preNumSuf(p: Option[String], n: Option[Int], s: Option[String]): Option[String] = join(Seq(p, n.map(_.toString), s), "")
  
  val word5 = "\\S{5,}".r
  
  /** change a random char after the 2nd to "~" */ 
  def wordTypo(s: String) = {
    val words = word5.findAllIn(s).matchData.map(m => (m.start, m.end)).toSeq
    val (str, end) = getRandomElement(words)
    val idx = 2 + str + Random.nextInt(end - str - 2)
    s.substring(0, idx) + "~" + s.substring(idx + 1)
  }
  
  /** add a typo to a random word longer than 4 chars */
  def mkTypo(seq: Seq[Option[String]]) = {
    val idxNotTooShort = for {
      (o, i) <- seq.zipWithIndex
      s <- o if word5.findAllIn(s).nonEmpty
    } yield i
    if (idxNotTooShort.isEmpty) seq // no word longer than 4 chars so no typo
    else {
      val idx = getRandomElement(idxNotTooShort)
      for {
        (o, i) <- seq.zipWithIndex
      } yield if (i == idx) o.map(wordTypo) else o
    }
  }
  
  def toAddresses(c: CliOption, loc: LocalityRow, seqLocAlias: Seq[LocalityAliasRow], numAddr: Int)(implicit db: Database, lookups: Lookups): Future[Seq[Addr]] = {
    for {
      seqAddr <- addressDetail(c, loc.localityPid)
      stateMap <- lookups.stateMap
      flatTypeMap <- lookups.flatTypeMap
      levelTypeMap <- lookups.levelTypeMap
      streetSuffixMap <- lookups.streetSuffixMap
    } yield {
      if (seqAddr.size >= numAddr) log.info(s"addresses for locality ${loc.localityPid} ${loc.localityName}: got $numAddr")
      else log.warn(s"addresses for locality ${loc.localityPid} ${loc.localityName}: want $numAddr, got ${seqAddr.size}")
      Random.shuffle(seqAddr).take(numAddr).map { case (
        // copied from AddressDetail.*
        addressDetailPid :: dateCreated :: dateLastModified :: dateRetired :: buildingName :: lotNumberPrefix :: lotNumber :: lotNumberSuffix ::
        flatTypeCode :: flatNumberPrefix :: flatNumber :: flatNumberSuffix ::
        levelTypeCode :: levelNumberPrefix :: levelNumber :: levelNumberSuffix ::
        numberFirstPrefix :: numberFirst :: numberFirstSuffix ::
        numberLastPrefix :: numberLast :: numberLastSuffix ::
        streetLocalityPid :: locationDescription :: localityPid :: aliasPrincipal :: postcode :: privateStreet :: legalParcelId :: confidence ::
        addressSitePid :: levelGeocodedCode :: propertyPid :: gnafPropertyPid :: primarySecondary :: HNil,
        as, sl, seqSla) =>
        
        // take postcode from AddressDetail - see gnaf-createdb/README.md for reason
          
        val (localityName, statePid) = if (seqLocAlias.nonEmpty) {
          val la = getRandomElement(seqLocAlias)
          (la.name, la.statePid)
        } else (loc.localityName, loc.statePid)
        
        val (streetName, streetTypeCode, streetSuffixCode) = if (seqSla.nonEmpty) {
          val sla = getRandomElement(seqSla)
          (sla.streetName, sla.streetTypeCode, sla.streetSuffixCode)
        } else (sl.streetName, sl.streetTypeCode, sl.streetSuffixCode)
        
        val flatNum = preNumSuf(flatNumberPrefix, flatNumber, flatNumberSuffix)
        val levelNum = preNumSuf(levelNumberPrefix, levelNumber, levelNumberSuffix)
        val flatLevel = if (c.noFlatType && flatNum.isDefined && levelNum.isEmpty)
            Seq(flatNum)
          else Seq(
            flatTypeCode.map(flatTypeMap), flatNum,
            levelTypeCode.map(levelTypeMap), levelNum
            )
        
        val qSeq = Seq(as.addressSiteName, buildingName) ++ flatLevel ++ Seq(
          join(Seq(preNumSuf(numberFirstPrefix, numberFirst, numberFirstSuffix), preNumSuf(numberLastPrefix, numberLast, numberLastSuffix)), "-"),
          Some(streetName), streetTypeCode, streetSuffixCode.map(streetSuffixMap),
          Some(localityName)
        )
        
        val state = Some(stateMap(statePid))
        val query = join(qSeq ++ Seq(state, postcode), " ").getOrElse("")
        val queryPostcodeBeforeState = join(qSeq ++ Seq(postcode, state), " ").getOrElse("")
        val queryTypo = join(mkTypo(qSeq) ++ Seq(state, postcode), " ").getOrElse("")
        
        // as above but not using locality and street aliases
        val address = join(Seq(
            as.addressSiteName,
            buildingName,
            flatTypeCode.map(flatTypeMap), preNumSuf(flatNumberPrefix, flatNumber, flatNumberSuffix),
            levelTypeCode.map(levelTypeMap), preNumSuf(levelNumberPrefix, levelNumber, levelNumberSuffix),
            join(Seq(preNumSuf(numberFirstPrefix, numberFirst, numberFirstSuffix), preNumSuf(numberLastPrefix, numberLast, numberLastSuffix)), "-"),
            Some(sl.streetName), sl.streetTypeCode, sl.streetSuffixCode.map(streetSuffixMap),
            Some(loc.localityName), Some(stateMap(loc.statePid)), postcode
        ), " ").getOrElse("")
        
        Addr(query, queryPostcodeBeforeState, queryTypo, addressDetailPid, address)
      }
    }
  }
  
 }