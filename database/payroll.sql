-- ============================================
-- SMARTPAYROLL PRO - DATABASE SETUP
-- MySQL 8.0+
-- ============================================

CREATE DATABASE IF NOT EXISTS payroll_db;
USE payroll_db;

-- ============================================
-- 1. REMOVE OLD TABLES
-- WARNING: THIS DELETES DATA IN THESE TABLES
-- ============================================

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS salary_slip;
DROP TABLE IF EXISTS payroll;
DROP TABLE IF EXISTS user_account;
DROP TABLE IF EXISTS employee;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================
-- 2. EMPLOYEE TABLE
-- ============================================

CREATE TABLE employee (
    employee_id VARCHAR(20) PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    designation VARCHAR(100) NOT NULL,
    department VARCHAR(100) NOT NULL DEFAULT 'General',

    employee_type VARCHAR(30) NOT NULL DEFAULT 'Full-time',
    employment_type VARCHAR(30) NOT NULL DEFAULT 'Full-time',

    basic_salary DECIMAL(12,2) NOT NULL DEFAULT 0.00,

    hra_percent DECIMAL(5,2) NOT NULL DEFAULT 20.00,
    da_percent DECIMAL(5,2) NOT NULL DEFAULT 10.00,
    travel_allowance DECIMAL(12,2) NOT NULL DEFAULT 1000.00,
    pf_percent DECIMAL(5,2) NOT NULL DEFAULT 12.00,
    insurance DECIMAL(12,2) NOT NULL DEFAULT 500.00,
    tax_percent DECIMAL(5,2) NOT NULL DEFAULT 5.00,

    email VARCHAR(150) DEFAULT NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 3. USER ACCOUNT TABLE
-- Stores administrator and employee login data
-- ============================================

CREATE TABLE user_account (
    username VARCHAR(100) PRIMARY KEY,
    role VARCHAR(20) NOT NULL,
    employee_id VARCHAR(20) DEFAULT NULL UNIQUE,

    salt VARCHAR(100) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_user_employee
        FOREIGN KEY (employee_id)
        REFERENCES employee(employee_id)
        ON DELETE CASCADE
);

-- ============================================
-- 4. PAYROLL TABLE
-- Stores monthly salary calculations
-- ============================================

CREATE TABLE payroll (
    payroll_id BIGINT AUTO_INCREMENT PRIMARY KEY,

    employee_id VARCHAR(20) NOT NULL,
    payroll_month DATE NOT NULL,

    basic_salary DECIMAL(12,2) NOT NULL DEFAULT 0.00,

    hra DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    da DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    travel_allowance DECIMAL(12,2) NOT NULL DEFAULT 0.00,

    pf DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    insurance DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    tax DECIMAL(12,2) NOT NULL DEFAULT 0.00,

    gross_salary DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    net_salary DECIMAL(12,2) NOT NULL DEFAULT 0.00,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payroll_employee
        FOREIGN KEY (employee_id)
        REFERENCES employee(employee_id),

    CONSTRAINT uq_employee_month
        UNIQUE (employee_id, payroll_month)
);

-- ============================================
-- 5. SALARY SLIP TABLE
-- ============================================

CREATE TABLE salary_slip (
    slip_id BIGINT AUTO_INCREMENT PRIMARY KEY,

    employee_id VARCHAR(20) NOT NULL,
    payroll_id BIGINT NOT NULL,

    payroll_month DATE NOT NULL,

    basic_salary DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    gross_salary DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    total_deductions DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    net_salary DECIMAL(12,2) NOT NULL DEFAULT 0.00,

    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_slip_employee
        FOREIGN KEY (employee_id)
        REFERENCES employee(employee_id),

    CONSTRAINT fk_slip_payroll
        FOREIGN KEY (payroll_id)
        REFERENCES payroll(payroll_id),

    CONSTRAINT uq_slip_payroll
        UNIQUE (payroll_id)
);

-- ============================================
-- 6. INDEXES
-- ============================================

CREATE INDEX idx_employee_department
    ON employee(department);

CREATE INDEX idx_payroll_month
    ON payroll(payroll_month);

CREATE INDEX idx_slip_month
    ON salary_slip(payroll_month);

-- ============================================
-- 7. VERIFY TABLES
-- ============================================

SHOW TABLES;

SHOW COLUMNS FROM employee;
SHOW COLUMNS FROM user_account;
SHOW COLUMNS FROM payroll;
SHOW COLUMNS FROM salary_slip;

-- ============================================
-- END OF SMARTPAYROLL DATABASE SETUP
-- ============================================