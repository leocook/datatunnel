### 发布打包
```
datax 依赖 bee: https://gitee.com/melin/bee，请先获取代码，本地编译打包。

mvn clean package -DlibScope=provided -Dmaven.test.skip=true
```

### 部署

解压 assembly/target/ 目录下生成可用包 datax-[version].tar.gz。复制所有jar 到 spark_home/jars 
在conf/spark-default.conf 添加配置: spark.sql.extensions com.dataworker.datax.core.DataxExtensions

### datax sql 语法
```sql
datax reader('数据类型名称') options(键值对参数) writer('数据类型名称') options(键值对参数)
```

### example
```sql
datax reader("jdbc") options(
    username="dataworks",
    password="dataworks2021",
    type="mysql",
    url="jdbc:mysql://10.5.20.20:3306",
    databaseName='dataworks', tableName='dc_datax_datasource', column=["*"])
    writer("hive") options(databaseName="bigdata", tableName='hive_datax_datasource', writeMode='overwrite', column=["*"]);
    
datax reader("hive") options(
        databaseName="bigdata", 
        tableName='hive_datax_datasource', 
        column=['id', 'code', 'type', 'description', 'config', 'gmt_created', 'gmt_modified', 'creater', 'modifier'])
    writer("jdbc") options(
        username="dataworks",
        password="dataworks2021",
        type="mysql",
        url="jdbc:mysql://10.5.20.20:3306",
        databaseName='dataworks', 
        tableName='dc_datax_datasource_copy1', 
        writeMode='overwrite',
        truncate=true,
        column=['id', 'code', 'dstype', 'description', 'config', 'gmt_created', 'gmt_modified', 'creater', 'modifier'])
```

### 参考

1. https://github.com/vladimir-bukhtoyarov/bucket4j

