CNT4007 Project
---------------
Compilation
-----------     
From Project directory root run:
javac -d out src/*.java

Running the Peers (Locally)
-----------------
In different terminals run:   

java -cp out peerProcess 1001    
java -cp out peerProcess 1002    
java -cp out peerProcess 1003


Multi-Device Setup (3 devices)          
Adjust Peerinfo.cfg to:       
1001 <host-or-ip-of-machine-1> 6008 1         
1002 <host-or-ip-of-machine-2> 6009 0         
1003 <host-or-ip-of-machine-3> 6010 0       
