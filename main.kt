// Package declaration indicating the namespace of the Kotlin file
package me.lucas.consensus


// Importing necessary classes and packages from the Java standard library
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface
import kotlin.system.exitProcess

// Just a small rundown, I’m just parsing arguments, cloning everything, updating it, 
// and then changing how the etcd command runs based off those arguments with different env variables 


// This function extension allows a string to be executed as a process within a specified directory.
fun String.process(directory: File) {
    // Creating a ProcessBuilder to execute the string as a process
    ProcessBuilder().apply {
        // Setting the working directory for the process
        directory(directory)
        // Defining the command to be executed as a list of strings
        command(listOf("/bin/bash", "-c", this@process))
        // redirecting error stream to standard output
        redirectErrorStream(true)
        // Starting the process and retrieving the associated Process object
        val process = start()
        // Creating a BufferedReader to read the output of the process
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        // Reading and printing each line of the process output
        while (reader.readLine().also { line = it } != null) {
            println(line)
        }
        // Waiting for the process to finish executing before exiting
        process.waitFor()
    }
}

// Main function for the program
fun main(args: Array<String>) {

    // Extracting command line arguments
    val directory = args.find { it.startsWith("--directory=") }?.substringAfter("=")
    val algorithm = args.find { it.startsWith("--algorithm=") }?.substringAfter("=")
    val ips = args.find { it.startsWith("--ips=") }?.substringAfter("=")?.split(",")
    println("IPS: $ips")
    val failures = args.find { it.startsWith("--failures=") }?.substringAfter("=")?.toInt()

    // Function to handle invalid arguments and exit the program if necessary
    val guard: (Boolean, String) -> (Unit) = { invalid, message ->
        if (invalid) {
            println(message)
            exitProcess(1)
        }
    }

    // List of accepted algorithms
    val algorithms = arrayOf("bench", "raft", "rabia", "paxos", "pineapple", "pineapple-memory")

    // Checking and validating arguments
    guard(directory == null, "You must specify a directory with --directory=")
    guard(algorithm == null, "You must specify an algorithm with --algorithm= \nOptions - ${algorithms.joinToString(separator = ", ") { it }}")
    guard(algorithms.find { it.equals(algorithm, ignoreCase = false) } == null, "That is not an accepted algorithm \nOptions - ${algorithms.joinToString(separator = ", ") { it }}")
    guard(ips == null, "You must specify node ips seperated by commas with --ips=")
    ips!!; directory!!


    if (algorithm == "rabia" || algorithm == "paxos") {
        guard(failures == null, "You must specify an amount of failures with --failures=")
        guard(failures!! > ips.size, "Failures must be less than ${ips.size} nodes")
    }

    // Create directory if it doesn't exist

    val file = File(directory)
    if (!file.exists()) file.mkdirs()

    try {
        // Clone necessary repositories
        arrayOf(
                "git clone https://github.com/Exerosis/Raft.git",
                "git clone https://github.com/Exerosis/PineappleGo.git",
                "git clone https://github.com/Exerosis/ETCD.git",
                "git clone https://github.com/Exerosis/RabiaGo.git",
                "git clone https://github.com/Bompedy/RS-Paxos.git",
                "git clone https://github.com/Exerosis/go-ycsb.git"
        ).forEach { it.process(file) }

        // If algorithm is "bench", build and make go-ycsb
        if (algorithm.equals("bench")) {
            "cd go-ycsb && sudo make".trimIndent().process(file)
            return
        }

        // Find IP address of the current machine
        val interfaces = NetworkInterface.getNetworkInterfaces()
        var ip: String? = null
        while (interfaces.hasMoreElements()) {
            val next = interfaces.nextElement()
            val addresses = next.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (ips.contains(address.hostAddress)) {
                    ip = address.hostAddress
                }
            }
        }

        // Check if IP is found, exit if not found
        println("ip: $ip")
        guard(ip == null, "Can't find host!")

        // Construct host name and print information
        val hostName = "node-${ips.indexOf(ip) + 1}"
//        guard(host == -1, "Can't find host address.")
        println("Host: $hostName")
        println("IP: $ip")
        println("Algorithim: $algorithm")

        // Construct setup string based on the algorithm
        val setup = if (algorithm == "raft") ips.joinToString(prefix = "--initial-cluster ", separator = ",") {
            "node-${ips.indexOf(it) + 1}=http://$it:12380"
        } else ""


        // Set up environment variables and execute etcd command
        ProcessBuilder().apply {
            environment()["NODES"] = ips.joinToString(separator = ",") { it }
            environment()["RS_RABIA"] = (algorithm == "rabia").toString()
            environment()["RS_PAXOS"] = (algorithm == "paxos").toString()
            environment()["PINEAPPLE"] = (algorithm == "pineapple").toString()
            environment()["PINEAPPLE_MEMORY"] = (algorithm == "pineapple-memory").toString()
            environment()["FAILURES"] = failures?.toString() ?: "0"
            directory(file)

            // Execute etcd command with the specified configuration
            command(listOf("/bin/bash", "-c", """
                git config --global --add safe.directory $directory/Raft &&
                git config --global --add safe.directory $directory/RabiaGo &&
                git config --global --add safe.directory $directory/PineappleGo &&
                git config --global --add safe.directory $directory/ETCD &&
                git config --global --add safe.directory $directory/RS-Paxos &&
                cd PineappleGo && git pull && cd .. &&
                cd RabiaGo && git pull && cd .. &&
                cd Raft && git pull && cd .. &&
                cd RS-Paxos && git pull && cd .. &&
                cd ETCD && git pull && rm -rf $hostName.etcd && make build &&
                ./bin/etcd \
                --log-level panic \
                --name "$hostName" \
                --initial-cluster-token etcd-cluster-1 \
                --listen-client-urls http://$ip:2379,http://127.0.0.1:2379 \
                --advertise-client-urls http://$ip:2379 \
                --initial-advertise-peer-urls http://$ip:12380 \
                --listen-peer-urls http://$ip:12380 \
                --quota-backend-bytes 10000000000 \
                --snapshot-count 0 \
                --max-request-bytes 104857600 \
                $setup \
                --initial-cluster-state new
            """.trimIndent()))

            // Redirect error stream and wait for the process to finish
            redirectErrorStream(true)
            val process = start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                println(line)
            }
            process.waitFor()
        }
    } catch (exception: Exception) {
        // Handle exceptions
        exception.printStackTrace()
    }
}