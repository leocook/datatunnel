package com.superior.datatunnel.examples.redshift

import com.superior.datatunnel.core.DataTunnelExtensions
import org.apache.spark.sql.SparkSession

object Mysql2RedshiftDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val accessKeyId = ""
        val secretAccessKey = ""
        val redshiftRoleArn = "arn:aws:iam::480976988805:role/service-role/AmazonRedshift-CommandsAccessRole-20230629T144155"

        val spark = SparkSession
                .builder()
                .master("local")
                .enableHiveSupport()
                .appName("Datatunnel spark example")
                .config("spark.sql.extensions", DataTunnelExtensions::class.java.name)
                .getOrCreate()

        val sql = """
            DATATUNNEL SOURCE("mysql") OPTIONS (
                username = "root",
                password = "root2023",
                host = '172.18.5.44',
                port = 3306,
                databaseName = 'demos',
                tableName = 'users',
                columns = ["*"],
                resultTableName='tdl_users'
            ) 
            SINK("redshift") OPTIONS (
                username = "admin",
                password = "Admin2023",
                host = '',
                port = 5439,
                databaseName = 'dev',
                schemaName = 'public',
                tableName = 'users',
                tempdir = 's3a://datacyber/redshift_temp/',
                region = 'us-east-1',
                accessKeyId = '${accessKeyId}',
                secretAccessKey = '${secretAccessKey}',
                redshiftRoleArn = '${redshiftRoleArn}',
                columns = ["*"]
            ) 
        """.trimIndent()

        spark.sql(sql)
    }
}