package com.example.calloraai;

public class CallRecord {

    public String phoneNumber;
    public String status;
    public long timestamp;

    public CallRecord(String phoneNumber, String status, long timestamp) {
        this.phoneNumber = phoneNumber;
        this.status = status;
        this.timestamp = timestamp;
    }
}