import type { ReactElement, ReactNode } from "react";
import styles from "./Card.module.css";

export interface CardProps {
  title?: string;
  children: ReactNode;
  className?: string;
}

export function Card({ title, children, className }: CardProps): ReactElement {
  return (
    <section className={`${styles.card} ${className ?? ""}`}>
      {title ? <h2 className={styles.title}>{title}</h2> : null}
      {children}
    </section>
  );
}
