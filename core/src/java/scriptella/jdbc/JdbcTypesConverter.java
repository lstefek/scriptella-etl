/*
 * Copyright 2006-2012 The Scriptella Project Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package scriptella.jdbc;

import scriptella.util.IOUtils;

import java.io.Closeable;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Represents a converter for prepared statement parameters and result set columns.
 * <p>This class defines a strategy for handling specific parameters like {@link URL}
 * and by default provides a generic behaviour for any objects.
 * <p>Configuration by exception is the general philosophy of this class, i.e.
 * most of the conversions must be performed by a provided resultset/preparedstatement and
 * custom conversions are applied only in rare cases. One of these cases is BLOB/CLOB handling.
 * <p>Specific adapters of JDBC drivers may provide a subclass of this class to
 * allow custom conversion conforming with the general contract of this class.
 *
 * @author Fyodor Kupolov
 * @version 1.0
 */
class JdbcTypesConverter implements Closeable {
    //Closable resources are registered here to be disposed when
    //they go out of scope, e.g. next query row, or after executing an SQL statement
    private List<Closeable> resources;

    /**
     * Gets the value of the designated column in the current row of this ResultSet
     * object as an Object in the Java programming language.
     *
     * @param rs
     * @param index    column index.
     * @param jdbcType column {@link java.sql.Types JDBC type}
     * @return
     * @throws SQLException
     * @see ResultSet#getObject(int)
     */
    public Object getObject(final ResultSet rs, final int index, final int jdbcType) throws SQLException {
        Object result = null;
        switch (jdbcType) {
            case Types.DATE: //For date/timestamp use getTimestamp to keep hh,mm,ss if possible
            case Types.TIMESTAMP:
                return rs.getTimestamp(index);
            case Types.TIME:
                return rs.getTime(index);
            case Types.BLOB:
                return rs.getBlob(index);
            case Types.CLOB:
                try { result = (Object)rs.getClob(index); }
                catch (SQLException e)
                {   // e.printStackTrace();
                        // nektere jdbc drivery ignoruji clob a berou tyto sloupce jako string (SQLite)
                        result = (Object)rs.getString(index);
                }
                return result;
            case Types.LONGVARBINARY:
                InputStream is = rs.getBinaryStream(index);
                return is == null ? null : toBlob(is);
            case Types.LONGVARCHAR:
                Reader reader = rs.getCharacterStream(index);
                return reader == null ? null : toClob(reader);

            case Types.CHAR:
            case Types.VARCHAR:
            case Types.NUMERIC:
            case Types.DECIMAL:
            case Types.BIT:
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
            case Types.BINARY:
            case Types.VARBINARY:
                result = rs.getObject(index);
                if (result == null) {
                    return null;
                }
                return result;
        }

        if (rs.getClass().getName() == "sun.jdbc.odbc.JdbcOdbcResultSet") {
                        
                switch (jdbcType) {
            case Types.NVARCHAR:
            case Types.NCHAR:
                result = null;

                try {
                    result = (Object)rs.getString(index);
                } catch (SQLException e) {
                        try {
                        result = (Object)rs.getBytes(index);
                    } catch (SQLException e1) {
                        result = null;
                    }
                }
                if (result == null) {
                    return null;
                }
                return result;
                
            case Types.NCLOB:
                return rs.getClob(index);
            default:
                return rs.getString(index);
                }
        }
                
        result = rs.getObject(index);
        if (result == null) {
            return null;
        }
        return result;
    }


    /**
     * Sets the value of the designated parameter using the given object.
     * <p>Depending on the value type the concrete subclass of JdbcTypesConverter is chosen.
     *
     * @param preparedStatement prepared statement to set object.
     * @param index             he first parameter is 1, the second is 2, ...
     * @param value             the object containing the input parameter value
     * @throws SQLException
     */
    public void setObject(final PreparedStatement preparedStatement, final int index, final Object value) throws SQLException {
        //
        if (preparedStatement.getClass().getName() == "org.sqlite.PrepStmt" && value instanceof Clob) {
            preparedStatement.setObject(index, ((Clob)value).getSubString(1, (int)((Clob)value).length()));
        }
        //Choosing a setter strategy
        else if (value instanceof InputStream) {
            setBlob(preparedStatement, index, toBlob((InputStream) value));
        } else if (value instanceof Reader) {
            setClob(preparedStatement, index, toClob((Reader) value));
            //For BLOBs/CLOBs use JDBC 1.0 methods for compatibility
        } else if (value instanceof Blob) {
            setBlob(preparedStatement, index, (Blob) value);
        } else if (value instanceof Clob) {
            setClob(preparedStatement, index, (Clob) value);
        } else if (value instanceof Date) {
            setDateObject(preparedStatement, index, (Date) value);
        } else if (value instanceof Calendar) {
            preparedStatement.setTimestamp(index, new Timestamp(((Calendar) value).getTimeInMillis()), (Calendar) value);
        } else {
            try {
                preparedStatement.setObject(index, value);
            } catch (SQLException e) {
                if (value == null) { //Some drivers require type of NULL parameter.
                    preparedStatement.setNull(index, Types.VARCHAR);
                } else {
                    throw e;
                }
            }
        }
    }

    protected Blob toBlob(InputStream is) {
        Blob blob = Lobs.newBlob(is);
        if (blob instanceof Closeable) {
            registerResource((Closeable) blob);
        }
        return blob;
    }

    protected Clob toClob(Reader reader) {
        Clob clob = Lobs.newClob(reader);
        if (clob instanceof Closeable) {
            registerResource((Closeable) clob);
        }
        return clob;
    }

    protected void setBlob(final PreparedStatement ps, final int index, final Blob blob) throws SQLException {
        InputStream stream = blob.getBinaryStream();
        ps.setBinaryStream(index, stream, (int) blob.length());
        registerResource(stream);
    }

    protected void setClob(final PreparedStatement ps, final int index, final Clob clob) throws SQLException {
        Reader reader = clob.getCharacterStream();
        ps.setCharacterStream(index, reader, (int) clob.length());
        registerResource(reader);
    }

    /**
     * Sets the {@link java.util.Date} or its descendant as a statement parameter.
     */
    protected void setDateObject(final PreparedStatement ps, final int index, final Date date) throws SQLException {
        if (date instanceof Timestamp) {
            ps.setTimestamp(index, (Timestamp) date);
        } else if (date instanceof java.sql.Date) {
            ps.setDate(index, (java.sql.Date) date);
        } else if (date instanceof Time) {
            ps.setTime(index, (Time) date);
        } else {
            ps.setTimestamp(index, new Timestamp(date.getTime()));
        }
    }

    protected void registerResource(Closeable resource) {
        if (resources == null) {
            resources = new ArrayList<Closeable>();
        }
        resources.add(resource);
    }

    /**
     * Closes any resources opened during this object lifecycle.
     */
    public void close() {
        if (resources != null) {
            IOUtils.closeSilently(resources);
            resources = null;
        }
    }


}
