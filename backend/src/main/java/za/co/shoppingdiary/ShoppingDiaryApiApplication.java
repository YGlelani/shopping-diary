package za.co.shoppingdiary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ShoppingDiaryApiApplication {
	public static void main(String[] args) {
		SpringApplication.run(ShoppingDiaryApiApplication.class, args);
	}
}