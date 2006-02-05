#!/bin/sh
cd `dirname $0`

if [ -x `which wget` ]
then
	port=`cat DATA/SETTINGS/httpProxy.conf |grep "^port="|sed "s/.*=//"`
	pw=`cat DATA/SETTINGS/httpProxy.conf |grep "^adminAccountBase64MD5="|sed "s/.*=//"`
	wget -q -t 1 --header "Authorization: realm=$pw" http://localhost:$port/Steering.html?shutdown=true -O /dev/null

echo "Please wait until the YaCy daemon process terminates"
echo "You can monitor this with 'tail -f DATA/LOG/yacy00.log' and 'fuser log/yacy00.log'"

elif [ -x `which java` ]
then
	# generating the proper classpath
	CLASSPATH=""
	for N in lib/*.jar; do CLASSPATH="$CLASSPATH$N:"; done	
	for N in libx/*.jar; do CLASSPATH="$CLASSPATH$N:"; done

	java -classpath classes:htroot:$CLASSPATH yacy -shutdown

	echo "Please wait until the YaCy daemon process terminates"
	echo "You can monitor this with 'tail -f DATA/LOG/yacy00.log' and 'fuser log/yacy00.log'"

else
	echo "Neither wget nor java could be found."
	echo "visit http://localhost:8080/Steering.html?shutdown=true to stop YaCy or use ./killYACY.sh"
fi
