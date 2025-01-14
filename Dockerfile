FROM public.ecr.aws/emr-serverless/spark/emr-6.15.0:latest

USER root
# MODIFICATIONS GO HERE
COPY assembly/target/datatunnel-3.4.0/*.jar /usr/lib/spark/jars/
# EMRS will run the image as hadoop
USER hadoop:hadoop