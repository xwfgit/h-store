#!/usr/bin/env python
# -*- coding: utf-8 -*-

import os
import sys
import re
import json
import logging
import getopt
import string
import time
from pprint import pprint

from hstoretraces import *

logging.basicConfig(level = logging.INFO,
                    format="%(asctime)s [%(funcName)s:%(lineno)03d] %(levelname)-5s: %(message)s",
                    datefmt="%m-%d-%Y %H:%M:%S",
                    stream = sys.stderr)

## ==============================================
## GLOBAL CONFIGURATION PARAMETERS
## ==============================================

## ==============================================
## main
## ==============================================
if __name__ == '__main__':
    _options, args = getopt.gnu_getopt(sys.argv[1:], '', [
        ## Input trace file (default is stdin)
        "trace=",
        ## The specific trace id to grab
        "id=",
        ## Parameter mapping
        "param-map=",
        ## Transaction Offset
        "offset=",
        ## Transaction Limit
        "limit=",
        ## Enable debug logging
        "debug",
    ])
    ## ----------------------------------------------
    ## COMMAND OPTIONS
    ## ----------------------------------------------
    options = { }
    for key, value in _options:
       if key.startswith("--"): key = key[2:]
       if key in options:
          options[key].append(value)
       else:
          options[key] = [ value ]
    ## FOR
    if "debug" in options: logging.getLogger().setLevel(logging.DEBUG)

    args = map(string.strip, args)
    trace_file = options["trace"][0] if "trace" in options else "-"
    command = args.pop(0)
    
    offset = int(options["offset"][0]) if "offset" in options else None
    limit = int(options["limit"][0]) if "limit" in options else None
    lookup_id = int(options["id"][0]) if "id" in options else None
    txn_ctr = -1
    limit_ctr = 0
    count_data = { }
    
    ## Parameter Mapping
    param_mappings = None
    if "param-map" in options:
        json_file = options["param-map"][0]
        with open(json_file, "r") as fd:
            param_mappings = json.load(fd)
        ## WITH
    ## IF

    #logging.debug("Trace: %s" % trace_file)
    logging.debug("Command:    %s" % command)
    logging.debug("Parameters: [%s]" % ",".join(args))
    logging.debug("Options:    [offset=%s, limit=%s, lookup_id=%s]" % (str(offset), str(limit), str(lookup_id)))
    with open(trace_file, "r") if trace_file != "-" else sys.stdin as fd:
        for line in map(string.strip, fd):
            txn_ctr += 1
            if txn_ctr > 0 and txn_ctr % 10000 == 0: logging.info("Transaction #%05d" % txn_ctr)
            if offset != None and txn_ctr < offset: continue
            if limit != None and limit_ctr >= limit: break
            json_data = json.loads(line)
            catalog_name = json_data["CATALOG_NAME"]
            trace_id = int(json_data["ID"])
            
            ## ----------------------------------------------
            ## GET
            ## ----------------------------------------------
            if command == "get":
                if catalog_name == args[0] and (lookup_id == None or lookup_id == trace_id):
                    txn = TransactionTrace().fromJSON(json_data)
                    assert txn
                    
                    if len(args) > 1 and len(txn.getQueries(args[1])) == 0: continue
                    print "[%05d] %s" % (txn_ctr, txn.catalog_name)
                    print json.dumps(txn.toJSON(), indent=2)
                    break
            ## ----------------------------------------------
            ## FIX
            ## ----------------------------------------------
            elif command == "fixparams":
                assert param_mappings
                if True or catalog_name == args[0] and (lookup_id == None or lookup_id == trace_id):
                    txn = TransactionTrace().fromJSON(json_data)
                    assert txn
                    
                    txn_param_map = param_mappings[catalog_name]
                    updated = False
                    for txn_param_idx in range(len(txn.params)):
                        if txn.params[txn_param_idx] != None or txn_param_map[txn_param_idx][1] == None: continue
                        if type(txn_param_map[txn_param_idx][1]) != list:
                            txn_param_map[txn_param_idx][1] = [ txn_param_map[txn_param_idx][1] ]
                            txn_param_map[txn_param_idx][2] = [ txn_param_map[txn_param_idx][2] ]
                        for ii in range(len(txn_param_map[txn_param_idx][1])):
                            query_name = txn_param_map[txn_param_idx][1][ii]
                            query_param_idx = txn_param_map[txn_param_idx][2][ii]
                            
                            for query in txn.getQueries(query_name):
                                if query.params[query_param_idx] != None:
                                    logging.debug("Fixed %s parameter #%d using parameter %d from %s" % (catalog_name, txn_param_idx, query_param_idx, query_name))
                                    txn.params[txn_param_idx] = query.params[query_param_idx]
                                    updated = True
                                    break
                            ## FOR (Query Parameters)
                        ## FOR (Txn Parameter Query Mapping)
                    ## FOR (Txn Parameters)
                    if updated:
                        writeJSON(txn.toJSON(), sys.stdout)
                        if not catalog_name in count_data: count_data[catalog_name] = 0
                        count_data[catalog_name] += 1
                        #print "[%05d] %s" % (txn_ctr, txn.catalog_name)
                        #print json.dumps(txn.toJSON(), indent=2)
                    else:
                        print line
                else:
                    print line
                limit_ctr += 1

            ## ----------------------------------------------
            ## EXTRACT
            ## ----------------------------------------------
            elif command == "extract":
                if catalog_name in args:
                    print line
                    limit_ctr += 1
            ## ----------------------------------------------
            ## FILTER
            ## ----------------------------------------------
            elif command == "filter":
                if not catalog_name in args:
                    print line
                    limit_ctr += 1
            ## ----------------------------------------------
            ## COUNT
            ## ----------------------------------------------
            elif command == "count":
                if len(args) == 0 or catalog_name in args:
                    if not catalog_name in count_data: count_data[catalog_name] = 0
                    count_data[catalog_name] += 1
                    limit_ctr += 1
                ## IF
            ## IF
        ## FOR
    ## WITH
    if count_data:
        if command == "fixparams":
            logging.debug(str(count_data))
        else:
            print "%-25s%s" % ("Procedure", "Txn Count")
            print "-"*35
            total = 0
            for key in sorted(count_data.keys()):
                print "%-25s%d" % (key, count_data[key])
                total += count_data[key]
            ## FOR
            print "-"*35
            print "%-25s%d" % ("TOTAL", total)
## MAIN