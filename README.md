# SDIS - P2P Chord Storage System

**2017/2018** - 3rd Year, 2nd Semester

**Course:** *Sistemas Distribuidos* ([SDIS](https://sigarra.up.pt/feup/en/UCURR_GERAL.FICHA_UC_VIEW?pv_ocorrencia_id=436906)) | Distribuited Systems

**Authors:** André Rocha ([andrefmrocha](https://github.com/andrefmrocha)) David Silva ([daviddias99](https://github.com/daviddias99)), Manuel Coutinho ([ManelCoutinho](https://github.com/ManelCoutinho)) and Mario Mesquita ([gambuzx](https://github.com/gambuzx))

---

**Description:** 

In this project we develope a peer-to-peer distributed backup service for the Internet. The idea is to use the free disk space of the computers on the Internet for backing up files in one's own computer. As in the [first project](https://github.com/daviddias99/distribuited-backup-service-feup-sdis), the service must support the backup, restore and deletion of files. Also, the participants in the service must retain total control over their own storage, and therefore they may delete copies of files that they have previously stored.

We ended up implementing a solution that uses the Chord protocol do create a distribuited hash table to be able to manage the chunks of a distribuited file. During the project we took care to keep the system scalable making use of concurrency (through thread pools when we could). This project gave us the chance to work on our protocol design skills.

For more information refer to the [design docs](docs/report.pdf) and the [project specification](docs/SDIS%202019_2020_%20Project%20--%20Distributed%20Backup%20Service%20For%20the%20Internet.pdf).

**Technologies:** Java

**Skills:** Distribuited Systems, Chord, Java NIO, protocol design, Message protocols, RMI, TCP, file management, concurrency, thread pools, fault tolerance.

**Grade:** 17.84/20

**Previous work:** [Project 1](https://github.com/daviddias99/distribuited-backup-service-feup-sdis)

---


