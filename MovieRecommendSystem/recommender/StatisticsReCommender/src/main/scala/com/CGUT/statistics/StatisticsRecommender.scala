package com.CGUT.statistics

import java.text.SimpleDateFormat
import java.util.Date

import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}

case class Movie(mid: Int, name: String, descri: String, timelong: String,
                 issue: String, shoot: String, language: String,
                 genres: String, actors: String, directors: String)

case class Rating(uid: Int, mid: Int, score: Double, timestamp: Int)

/**
 *
 * @param uri MongoDB连接
 * @param db  MongoDB数据库
 */
case class MongoConfig(uri: String, db: String)

//定义一个基准推荐对象
case class Recommendation(mid: Int, score: Double)

//定义电影类别top10推荐对象
case class GenresRecommendation(genre: String, recs: Seq[Recommendation])

object StatisticsRecommender {
  def main(args: Array[String]): Unit = {
    // 定义表名
    val MONGODB_MOVIE_COLLECTION = "Movie"
    val MONGODB_RATING_COLLECTION = "Rating"
    // 统计的表的名称
    val RATE_MORE_MOVIES = "RateMoreMovies"
    val RATE_MORE_RECENTLY_MOVIES = "RateMoreRecentlyMovies"
    val AVERAGE_MOVIES = "AverageMovies"
    val GENRES_TOP_MOVIES = "GenresTopMovies"
    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://CentOS-7-107:27017/recommender",
      "mongo.db" -> "recommender"
    )
    // 创建一个sparkConf
    val sparkConf = new SparkConf().setAppName("StatisticsRecommender").setMaster(config("spark.cores"))

    // 创建一个SparkSession
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()

    import spark.implicits._

    implicit val mongoConfig = MongoConfig(config("mongo.uri"), config("mongo.db"))

    // 从mongoDB里面加载数据
    val ratingDF = spark.read
      .option("uri", mongoConfig.uri)
      .option("collection", MONGODB_RATING_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[Rating]
      .toDF()

    val movieDF = spark.read
      .option("uri", mongoConfig.uri)
      .option("collection", MONGODB_MOVIE_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[Movie]
      .toDF()
    // 创建名为ratings的临时表
    ratingDF.createOrReplaceTempView("ratings")
    // TODO:不同的统计结果
    // 1、历史热门统计，历史评分数据最多, mid, count
    val rateMoreMoviesDF = spark.sql("select mid, count(mid) as count from ratings group by mid")
    // 把结果写入到MongoDB表中
    storeDFInMongoDB(rateMoreMoviesDF, RATE_MORE_MOVIES)
    // 2、近期热门统计，按照“yyyyMM”格式选取最近的评分数据，统计评分个数
    //创建一个日期格式化工具
    val simpleDateFormat = new SimpleDateFormat("yyyyMM")
    //注册udf，把时间戳转换成年月格式
    spark.udf.register("changeDate", (x: Int) => simpleDateFormat.format(new Date(x * 1000L)).toInt)
    //对原始数据做预处理，去掉uid
    val ratingOfYearMonth = spark.sql("select mid, score , changeDate(timestamp) as yearmonth from ratings")
    // 将新的数据集注册成为一张表
    ratingOfYearMonth.createOrReplaceTempView("ratingOfMonth")
    //从ratingOfMonth中查找电影在各个月份的评分，mid，count，yearmonth
    val rateMoreRecentlyMoviesDF = spark.sql("select mid, count(mid) as count, yearmonth from ratingOfMonth group by yearmonth, mid order by yearmonth desc, count desc")
    // 存入mongoDB
    storeDFInMongoDB(rateMoreRecentlyMoviesDF, RATE_MORE_RECENTLY_MOVIES)

    // 3、优质电影统计、统计电影的平均评分
    val averageMoviesDF = spark.sql("select mid, avg(score) as avg from ratings group by mid")
    storeDFInMongoDB(averageMoviesDF, AVERAGE_MOVIES)

    // 4、各类电影Top统计
    //把平均评分加入到movie表里，加一列，inner join
    val movieWithScore = movieDF.join(averageMoviesDF, Seq("mid"))

    //所有的电影类别
    val genres = List("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "Foreign",
      "History", "Horror", "Music", "Mystery", "Romance", "Science", "Tv", "Thriller", "War", "Western")

    //为做笛卡尔积，将电影类别转换成 RDD
    val genresRDD = spark.sparkContext.makeRDD(genres)

    //计算电影类别 top10,首先对类别和电影做笛卡尔积
    val genrenTopMoviesDF = genresRDD.cartesian(movieWithScore.rdd)
      .filter {
        //条件过滤，找出movie的字段genre值(Action|Adventure|Sci-Fi)包含当前genre(action)的那些
        case (genre, movieRow) => movieRow.getAs[String]("genres").toLowerCase.contains(genre.toLowerCase)
      }
      .map {
        // 将整个数据集的数据量减小，生成 RDD[String,Iter[mid,avg]]
        case (genre, movieRow) => (genre, (movieRow.getAs[Int]("mid"), movieRow.getAs[Double]("avg")))
      }
      .groupByKey()
      .map {
        case (genres, items) => GenresRecommendation(genres, items.toList.sortWith(_._2 > _._2).take(10).map(item => Recommendation(item._1, item._2)))
      }
      .toDF()
    //保存到mongoDB
    storeDFInMongoDB(genrenTopMoviesDF, GENRES_TOP_MOVIES)

    spark.stop()
  }

  def storeDFInMongoDB(df: DataFrame, collection_name: String)(implicit mongoConfig: MongoConfig): Unit = {
    df.write
      .option("uri", mongoConfig.uri)
      .option("collection", collection_name)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()
  }
}
