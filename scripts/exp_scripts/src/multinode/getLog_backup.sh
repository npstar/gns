output_folder=$1

log_folder=$2

#nozip="$1"
date
echo "Remove earlier log ..."
rm -rf log/*
mkdir -p log

echo "Create local directories ..."
cat pl_lns | parallel -j+100 mkdir log/log_lns_{} &
cat pl_ns | parallel -j+100 mkdir log/log_ns_{} &

#if [ "$nozip" != "--nozip" ]; then
echo "Gzip logs at LNS and NS ..."
cat pl_lns pl_ns > pl_xkcd
# remove console output files, delete log folder
cat pl_xkcd | parallel -j+100 ssh -i auspice.pem -oStrictHostKeyChecking=no -oConnectTimeout=60 ec2-user@{}  "rm $log_folder/log_*\; gzip $log_folder/log/*"
#echo "Gzip logs at NS ..."

date
echo "Copying config file from LNS ..."
cat pl_lns | parallel -j+100 scp -i auspice.pem -r -oStrictHostKeyChecking=no -oConnectTimeout=60 ec2-user@{}:pl_config log/log_lns_{} &

echo "Copying logs from LNS ..."
#echo "Copying gnrs_stat.xml files only ..."
cat pl_lns | parallel -j+100 scp -i auspice.pem -r -oStrictHostKeyChecking=no -oConnectTimeout=60 ec2-user@{}:$log_folder/log/* log/log_lns_{}

date
echo "Copying config file from NS ..."
cat pl_ns | parallel -j+100 scp -i auspice.pem -r -oStrictHostKeyChecking=no -oConnectTimeout=60 ec2-user@{}:pl_config log/log_ns_{} &

date
echo "Copying logs from NS ..."
cat pl_ns | parallel -j+100 scp -i auspice.pem -oStrictHostKeyChecking=no -oConnectTimeout=60 -r ec2-user@{}:$log_folder/log/* log/log_ns_{} 


#cat pl_ns | parallel -j+100 scp -i auspice.pem -r -oStrictHostKeyChecking=no -oConnectTimeout=60 ec2-user@{}:ping_output log/log_ns_{}
#cat pl_lns | parallel -j+100 scp -i auspice.pem -r -oStrictHostKeyChecking=no -oConnectTimeout=60 ec2-user@{}:ping_output log/log_lns_{}


# OLD
#cat pl_ns | parallel -j+100 scp -i auspice.pem -r -oStrictHostKeyChecking=no -oConnectTimeout=10 ec2-user@{}:log log/log_ns_{}
#For GNRS-westy
#cat pl_lns | parallel -j+100 scp -i auspice.pem -oStrictHostKeyChecking=no -oConnectTimeout=10 -r ec2-user@{}:log log/log_lns_{}

if [ "$output_folder" != "" ]; then
    mkdir -p $output_folder
    mv log/* $output_folder
fi
