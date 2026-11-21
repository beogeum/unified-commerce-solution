"use client";
import { ReactNode } from "react";

export type Column<T> = { key: string; header: string; num?: boolean; render: (row: T) => ReactNode };

export function DataTable<T>({ caption, columns, rows, rowKey, onRowClick, empty }: {
  caption: string;
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
  empty: ReactNode;
}) {
  if (rows.length === 0) return <div className="card">{empty}</div>;
  return (
    <div className="card tbl-wrap">
      <table>
        <caption className="ctx" style={{ textAlign: "left", padding: "10px 12px" }}>{caption} · {rows.length}건</caption>
        <thead>
          <tr>{columns.map((c) => <th key={c.key} scope="col" className={c.num ? "num" : undefined}>{c.header}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={rowKey(r)} className={onRowClick ? "clickable" : undefined} onClick={onRowClick ? () => onRowClick(r) : undefined}
              tabIndex={onRowClick ? 0 : undefined}
              onKeyDown={onRowClick ? (e) => { if (e.key === "Enter") onRowClick(r); } : undefined}>
              {columns.map((c) => <td key={c.key} className={c.num ? "num" : undefined}>{c.render(r)}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
